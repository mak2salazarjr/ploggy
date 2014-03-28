/*
 * Copyright (c) 2014, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package ca.psiphon.ploggy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Pair;
import ca.psiphon.ploggy.widgets.TimePickerPreference;

import com.squareup.otto.Subscribe;

/**
 * Coordinator for background Ploggy work.
 *
 * The Engine:
 * - schedules friend status push/pulls
 * - schedules friend resource downloads
 * - maintains a worker thread pool for background tasks (pushing/pulling
 *   friends and handling friend requests
 * - runs the local location monitor
 * - (re)-starts and stops the local web server and Tor Hidden Service to
 *   handle requests from friends
 *
 * An Engine instance is intended to be run via an Android Service set to
 * foreground mode (i.e., long running).
 */

/*

*IN PROGRESS*

- double-check Protocol.validate() called where required in Engine
- pull more fixes from NanoHttpd upstream
- Review all "*TODO*" comments

*/

public class Engine implements OnSharedPreferenceChangeListener, WebServer.RequestHandler {

    private static final String LOG_TAG = "Engine";

    public static final String DEFAULT_PLOGGY_INSTANCE_NAME = "ploggy";

    private final String mInstanceName;
    private final Context mContext;
    private final Data mData;
    private final Handler mHandler;
    private final SharedPreferences mSharedPreferences;
    private boolean mStopped;
    private Runnable mPreferencesRestartTask;
    private Runnable mTorTimeoutRestartTask;
    private Runnable mDownloadRetryTask;
    private ExecutorService mTaskThreadPool;
    private ExecutorService mPeerRequestThreadPool;
    enum FriendTaskType {ASK_PULL, ASK_LOCATION, PUSH_TO, PULL_FROM, DOWNLOAD_FROM};
    private Map<FriendTaskType, HashMap<String, Runnable>> mFriendTaskObjects;
    private Map<FriendTaskType, HashMap<String, Future<?>>> mFriendTaskFutures;
    private Map<String, ArrayList<Protocol.Payload>> mFriendPushQueue;
    private Set<String> mLocationRecipients;
    private LocationFixer mLocationFixer;
    private WebServer mWebServer;
    private TorWrapper mTorWrapper;
    private WebClientConnectionPool mWebClientConnectionPool;

    private static final int TOR_TIMEOUT_RESTART_IF_NOT_CONNECTED_IN_MILLISECONDS = 5*60*1000; // 5 min.
    private static final int TOR_TIMEOUT_RESTART_IF_NO_COMMUNICATION_IN_MILLISECONDS = 120*60*1000; // 2 hours

    private static final int PREFERENCE_CHANGE_RESTART_DELAY_IN_MILLISECONDS = 5*1000; // 5 sec.
    private static final int DOWNLOAD_RETRY_PERIOD_IN_MILLISECONDS = 10*60*1000; // 10 min.

    private static final int THREAD_POOL_SIZE = 30;

    // FRIEND_REQUEST_DELAY_IN_SECONDS is intended to compensate for
    // peer hidden service publish latency. Use this when scheduling requests
    // unless in response to a received peer communication (so, use it on
    // start up, or when a friend is added, for example).
    private static final int FRIEND_REQUEST_DELAY_IN_MILLISECONDS = 30*1000;

    public Engine() {
        this(Engine.DEFAULT_PLOGGY_INSTANCE_NAME);
    }

    public Engine(String instanceName) {
        Utils.initSecureRandom();
        mContext = Utils.getApplicationContext();
        mInstanceName = instanceName;
        mData = Data.getInstance(mInstanceName);
        mHandler = new Handler();
        // TODO: distinct instance of preferences for each persona
        // e.g., getSharedPreferencesName("persona1");
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        mStopped = true;
    }

    private String logTag() {
        return String.format("%s [%s]", LOG_TAG, mInstanceName);
    }

    public synchronized void start() throws PloggyError {
        if (!mStopped) {
            stop();
        }
        mStopped = false;
        Log.addEntry(logTag(), "starting...");
        Events.getInstance(mInstanceName).register(this);
        mTaskThreadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        // Using a distinct worker thread pool and queue to manage peer
        // requests, so local tasks are not blocked by peer actions.
        mPeerRequestThreadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        mFriendTaskObjects = new EnumMap<FriendTaskType, HashMap<String, Runnable>>(FriendTaskType.class);
        mFriendTaskFutures = new EnumMap<FriendTaskType, HashMap<String, Future<?>>>(FriendTaskType.class);
        mFriendPushQueue = new HashMap<String, ArrayList<Protocol.Payload>>();
        mLocationRecipients = new HashSet<String>();
        mLocationFixer = new LocationFixer(this);
        mLocationFixer.start();
        startHiddenService();
        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
        setTorTimeout(TOR_TIMEOUT_RESTART_IF_NOT_CONNECTED_IN_MILLISECONDS);
        Log.addEntry(logTag(), "started");
    }

    public synchronized void stop() {
        Log.addEntry(logTag(), "stopping...");
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        Events.getInstance(mInstanceName).unregister(this);
        cancelTorTimeout();
        if (mLocationFixer != null) {
            mLocationFixer.stop();
            mLocationFixer = null;
        }
        if (mFriendTaskObjects != null) {
            mFriendTaskObjects.clear();
            mFriendTaskObjects = null;
        }
        if (mFriendTaskFutures != null) {
            mFriendTaskFutures.clear();
            mFriendTaskFutures = null;
        }
        if (mFriendPushQueue != null) {
            mFriendPushQueue.clear();
            mFriendPushQueue = null;
        }
        if (mTaskThreadPool != null) {
            Utils.shutdownExecutorService(mTaskThreadPool);
            mTaskThreadPool = null;
        }
        if (mPeerRequestThreadPool != null) {
            Utils.shutdownExecutorService(mPeerRequestThreadPool);
            mPeerRequestThreadPool = null;
        }
        stopDownloadRetryTask();
        stopWebClientConnectionPool();
        stopHiddenService();
        mStopped = true;
        Log.addEntry(logTag(), "stopped");
    }

    @Override
    public synchronized void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Restart engine to apply changed preferences. Delay restart until user inputs are idle.
        // (This idle delay is important due to how SeekBarPreferences trigger onSharedPreferenceChanged
        // continuously as the user slides the seek bar). Delayed restart runs on main thread.
        if (mPreferencesRestartTask == null) {
            mPreferencesRestartTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        stop();
                        start();
                    } catch (PloggyError e) {
                        Log.addEntry(logTag(), "failed to restart engine after preference change");
                    }
                }
            };
        } else {
            mHandler.removeCallbacks(mPreferencesRestartTask);
        }
        mHandler.postDelayed(mPreferencesRestartTask, PREFERENCE_CHANGE_RESTART_DELAY_IN_MILLISECONDS);
    }

    private void setTorTimeout(int milliseconds) {
        if (mTorTimeoutRestartTask == null) {
            mTorTimeoutRestartTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        stop();
                        start();
                    } catch (PloggyError e) {
                        Log.addEntry(logTag(), "failed to restart engine after Tor timeout");
                    }
                }
            };
        } else {
            mHandler.removeCallbacks(mTorTimeoutRestartTask);
        }
        mHandler.postDelayed(mTorTimeoutRestartTask, milliseconds);
    }

    private void cancelTorTimeout() {
        if (mTorTimeoutRestartTask != null) {
            mHandler.removeCallbacks(mTorTimeoutRestartTask);
            mTorTimeoutRestartTask = null;
        }
    }

    @Subscribe
    public synchronized void onTorCircuitEstablished(Events.TorCircuitEstablished torCircuitEstablished) {
        try {
            setTorTimeout(TOR_TIMEOUT_RESTART_IF_NO_COMMUNICATION_IN_MILLISECONDS);
            startWebClientConnectionPool();
            // Ask friends to pull local, self changes...
            askPullFromFriends();
            // ...and pull changes from friends
            pullFromFriends();
            startDownloadRetryTask();
        } catch (PloggyError e) {
            Log.addEntry(logTag(), "failed to start friend poll after Tor circuit established");
        }
    }

    @Subscribe
    public synchronized void onUpdatedSelf(Events.UpdatedSelf updatedSelf) {
        // Apply new transport and hidden service credentials
        try {
            start();
        } catch (PloggyError e) {
            Log.addEntry(logTag(), "failed to restart hidden service after self updated");
        }
    }

    @Subscribe
    public synchronized void onAddedFriend(Events.AddedFriend addedFriend) {
        // Apply new set of friends to web server and pull schedule.
        // Friend poll will be started after Tor circuit is established.
        // TODO: don't need to restart Tor, just web server
        // (now need to restart Tor due to Hidden Service auth; but could use control interface instead?)
        try {
            start();
        } catch (PloggyError e) {
            Log.addEntry(logTag(), "failed restart sharing service after added friend");
        }
    }

    @Subscribe
    public synchronized void onUpdatedFriend(Events.UpdatedFriend updatedFriend) {
        // Update implies communication sent/received, so extend the restart the timeout
        setTorTimeout(TOR_TIMEOUT_RESTART_IF_NO_COMMUNICATION_IN_MILLISECONDS);
    }

    @Subscribe
    public synchronized void onRemovedFriend(Events.RemovedFriend removedFriend) {
        // Full stop/start to clear friend task cache
        try {
            start();
        } catch (PloggyError e) {
            Log.addEntry(logTag(), "failed restart sharing service after removed friend");
        }
    }

    @Subscribe
    public synchronized void onNewSelfLocationFix(Events.NewSelfLocationFix newSelfLocation) {
        try {
            String streetAddress;
            if (newSelfLocation.mAddress != null) {
                streetAddress = newSelfLocation.mAddress.toString();
            } else {
                streetAddress = "";
            }
            mData.putSelfLocation(
                    new Protocol.Location(
                            new Date(),
                            newSelfLocation.mLocation.getLatitude(),
                            newSelfLocation.mLocation.getLongitude(),
                            streetAddress));
        } catch (PloggyError e) {
            Log.addEntry(logTag(), "failed to update self status with new location");
        }
    }

    @Subscribe
    public synchronized void onUpdatedSelfGroup(Events.UpdatedSelfGroup updatedSelfGroup) {
        try {
            pushToFriends(mData.getGroupOrThrow(updatedSelfGroup.mGroupId).mGroup);
        } catch (PloggyError e) {
            Log.addEntry(logTag(), "failed push to friends after self group update");
        }
    }

    @Subscribe
    public synchronized void onUpdatedSelfLocation(Events.UpdatedSelfLocation updatedSelfLocation) {
        try {
            pushToFriends(mData.getSelfLocationOrThrow());
        } catch (PloggyError e) {
            Log.addEntry(logTag(), "failed push to friends after self location update");
        }
    }

    @Subscribe
    public synchronized void onUpdatedSelfPost(Events.UpdatedSelfPost updatedSelfPost) {
        try {
            pushToFriends(mData.getPostOrThrow(updatedSelfPost.mPostId).mPost);
        } catch (PloggyError e) {
            Log.addEntry(logTag(), "failed push to friends after self post update");
        }
    }

    @Subscribe
    public synchronized void onAddedDownload(Events.AddedDownload addedDownload) {
        // Schedule immediate download, if not already downloading from friend
        triggerFriendTask(FriendTaskType.DOWNLOAD_FROM, addedDownload.mFriendId);
    }

    public synchronized Future<?> submitTask(Runnable task) {
        if (mTaskThreadPool != null) {
            return mTaskThreadPool.submit(task);
        }
        return null;
    }

    @Override
    public synchronized void submitWebRequestTask(Runnable task) {
        if (mPeerRequestThreadPool != null) {
            mPeerRequestThreadPool.submit(task);
        }
    }

    private void startHiddenService() throws PloggyError {
        stopHiddenService();

        Data.Self self = mData.getSelfOrThrow();
        List<String> friendCertificates = new ArrayList<String>();
        for (Data.Friend friend : mData.getFriendsIterator()) {
            friendCertificates.add(friend.mPublicIdentity.mX509Certificate);
        }
        mWebServer = new WebServer(
                mData,
                this,
                new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                friendCertificates);
        try {
            mWebServer.start();
        } catch (IOException e) {
            throw new PloggyError(logTag(), e);
        }

        List<TorWrapper.HiddenServiceAuth> hiddenServiceAuths = new ArrayList<TorWrapper.HiddenServiceAuth>();
        for (Data.Friend friend : mData.getFriendsIterator()) {
            hiddenServiceAuths.add(
                    new TorWrapper.HiddenServiceAuth(
                            friend.mPublicIdentity.mHiddenServiceHostname,
                            friend.mPublicIdentity.mHiddenServiceAuthCookie));
        }
        mTorWrapper = new TorWrapper(
                mInstanceName,
                TorWrapper.Mode.MODE_RUN_SERVICES,
                hiddenServiceAuths,
                new HiddenService.KeyMaterial(
                        self.mPublicIdentity.mHiddenServiceHostname,
                        self.mPublicIdentity.mHiddenServiceAuthCookie,
                        self.mPrivateIdentity.mHiddenServicePrivateKey),
                mWebServer.getListeningPort());
        // TODO: in a background thread, monitor mTorWrapper.awaitStarted() to check for errors and retry...
        mTorWrapper.start();
        // Note: startFriendPoll is deferred until onTorCircuitEstablished
    }

    private void stopHiddenService() {
        // Friend poll depends on Tor wrapper, so stop it first
        stopDownloadRetryTask();
        if (mTorWrapper != null) {
            mTorWrapper.stop();
        }
        if (mWebServer != null) {
            mWebServer.stop();
        }
    }

    private void startWebClientConnectionPool() throws PloggyError {
        stopWebClientConnectionPool();
        mWebClientConnectionPool = new WebClientConnectionPool(mData, getTorSocksProxyPort());
    }

    private void stopWebClientConnectionPool() {
        if (mWebClientConnectionPool != null) {
            mWebClientConnectionPool.shutdown();
            mWebClientConnectionPool = null;
        }
    }

    public synchronized int getTorSocksProxyPort() throws PloggyError {
        if (mTorWrapper != null) {
            return mTorWrapper.getSocksProxyPort();
        }
        throw new PloggyError(logTag(), "no Tor socks proxy");
    }

    private synchronized void addFriendToReceiveLocation(String friendId) throws PloggyError {
        mLocationRecipients.add(friendId);
        mLocationFixer.start();
    }

    private synchronized void pushToFriends(Protocol.Location location) throws PloggyError {
        for (String friendId : mLocationRecipients) {
            enqueueFriendPushPayload(friendId, new Protocol.Payload(Protocol.Payload.Type.LOCATION, location));
            triggerFriendTask(FriendTaskType.PUSH_TO, friendId);
        }
        mLocationRecipients.clear();
    }

    private void askPullFromFriends() throws PloggyError {
        for (Data.Friend friend : mData.getFriendsIterator()) {
            triggerFriendTask(FriendTaskType.ASK_PULL, friend.mId);
        }
    }

    public void askLocationFromFriend(String friendId) throws PloggyError {
        triggerFriendTask(FriendTaskType.ASK_LOCATION, friendId);
    }

    private void pullFromFriends() throws PloggyError {
        for (Data.Friend friend : mData.getFriendsIterator()) {
            triggerFriendTask(FriendTaskType.PULL_FROM, friend.mId);
        }
    }

    private void pushToFriends(Protocol.Group group) throws PloggyError {
        pushToGroup(group, new Protocol.Payload(Protocol.Payload.Type.GROUP, group));
    }

    private void pushToFriends(Protocol.Post post) throws PloggyError {
        Data.Group group = mData.getGroupOrThrow(post.mGroupId);
        pushToGroup(group.mGroup, new Protocol.Payload(Protocol.Payload.Type.POST, post));
    }

    private void pushToGroup(Protocol.Group group, Protocol.Payload payload) throws PloggyError {
        for (Identity.PublicIdentity member : group.mMembers) {
            enqueueFriendPushPayload(member.mId, payload);
            triggerFriendTask(FriendTaskType.PUSH_TO, member.mId);
        }
    }

    private void startDownloadRetryTask() throws PloggyError {
        stopDownloadRetryTask();
        // Start a recurring timer with initial delay
        // FRIEND_REQUEST_DELAY_IN_MILLISECONDS and subsequent delay
        // DOWNLOAD_RETRY_PERIOD_IN_MILLISECONDS. The timer triggers
        // friend downloads, if any are pending.
        if (mDownloadRetryTask == null) {
            mDownloadRetryTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        for (Data.Friend friend : mData.getFriendsIterator()) {
                            triggerFriendTask(FriendTaskType.DOWNLOAD_FROM, friend.mId);
                        }
                    } catch (PloggyError e) {
                        Log.addEntry(logTag(), "failed to poll friends");
                    } finally {
                        mHandler.postDelayed(this, DOWNLOAD_RETRY_PERIOD_IN_MILLISECONDS);
                    }
                }
            };
        } else {
            mHandler.removeCallbacks(mDownloadRetryTask);
        }
        mHandler.postDelayed(mDownloadRetryTask, FRIEND_REQUEST_DELAY_IN_MILLISECONDS);
    }

    private void stopDownloadRetryTask() {
        if (mDownloadRetryTask != null) {
            mHandler.removeCallbacks(mDownloadRetryTask);
        }
    }

    private synchronized void triggerFriendTask(FriendTaskType taskType, String friendId) {
        // Schedules one push/pull/download per friend at a time.
        if (mFriendTaskObjects.get(taskType) == null) {
            mFriendTaskObjects.put(taskType, new HashMap<String, Runnable>());
        }
        // Cache instantiated task functions
        Runnable task = mFriendTaskObjects.get(taskType).get(friendId);
        if (task == null) {
            switch (taskType) {
            case ASK_PULL:
                task = makeAskPullToFriendTask(friendId);
                break;
            case ASK_LOCATION:
                task = makeAskLocationToFriendTask(friendId);
                break;
            case PUSH_TO:
                task = makePushToFriendTask(friendId);
                break;
            case PULL_FROM:
                task = makePullFromFriendTask(friendId);
                break;
            case DOWNLOAD_FROM:
                task = makeDownloadFromFriendTask(friendId);
                break;
            }
            mFriendTaskObjects.get(taskType).put(friendId, task);
        }
        if (mFriendTaskFutures.get(taskType) == null) {
            mFriendTaskFutures.put(taskType, new HashMap<String, Future<?>>());
        }
        // If a Future is present, the task is in progress.
        // On completion, tasks remove their Futures from mFriendTaskFutures.
        if (mFriendTaskFutures.get(taskType).get(friendId) != null) {
            return;
        }
        Future<?> future = submitTask(task);
        mFriendTaskFutures.get(taskType).put(friendId, future);
    }

    /*
    // *TODO* may be obsolete code
    private synchronized void cancelPendingFriendTask(FriendTaskType taskType, String friendId) {
        // Remove pending (not running) task, if present in queue
        Future<?> future = mFriendTaskFutures.get(taskType).get(friendId);
        if (future != null) {
            if (future.cancel(false)) {
                mFriendTaskFutures.get(taskType).remove(friendId);
            }
        }
    }
    */

    private synchronized void completedFriendTask(FriendTaskType taskType, String friendId) {
        mFriendTaskFutures.get(taskType).remove(friendId);
    }

    private synchronized void enqueueFriendPushPayload(String friendId, Protocol.Payload payload) {
        if (mFriendPushQueue.get(friendId) == null) {
            mFriendPushQueue.put(friendId, new ArrayList<Protocol.Payload>());
        }
        mFriendPushQueue.get(friendId).add(payload);
    }

    private synchronized Protocol.Payload dequeueFriendPushPayload(String friendId) {
        ArrayList<Protocol.Payload> queue = mFriendPushQueue.get(friendId);
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        return queue.remove(0);
    }

    // TODO: refactor common code in makeTask functions?

    private Runnable makeAskPullToFriendTask(String friendId) {
        final String finalFriendId = friendId;
        return new Runnable() {
            @Override
            public void run() {
                try {
                    if (!mTorWrapper.isCircuitEstablished()) {
                        return;
                    }
                    Data.Friend friend = mData.getFriendById(finalFriendId);
                    Log.addEntry(logTag(), "ask pull to: " + friend.mPublicIdentity.mNickname);
                    WebClientRequest webClientRequest =
                        new WebClientRequest(
                            mWebClientConnectionPool,
                            friend.mPublicIdentity.mHiddenServiceHostname,
                            Protocol.WEB_SERVER_VIRTUAL_PORT,
                            WebClientRequest.RequestType.GET,
                            Protocol.ASK_PULL_GET_REQUEST_PATH);
                    webClientRequest.makeRequest();
                } catch (Data.NotFoundError e) {
                    // Friend was deleted while task was enqueued. Ignore error.
                } catch (PloggyError e) {
                    try {
                        Log.addEntry(
                                logTag(),
                                "failed to ask pull to: " +
                                    mData.getFriendByIdOrThrow(finalFriendId).mPublicIdentity.mNickname);
                    } catch (PloggyError e2) {
                        Log.addEntry(logTag(), "failed to ask pull");
                    }
                } finally {
                    completedFriendTask(FriendTaskType.ASK_PULL, finalFriendId);
                }
            }
        };
    }

    private Runnable makeAskLocationToFriendTask(String friendId) {
        final String finalFriendId = friendId;
        return new Runnable() {
            @Override
            public void run() {
                try {
                    if (!mTorWrapper.isCircuitEstablished()) {
                        return;
                    }
                    Data.Friend friend = mData.getFriendById(finalFriendId);
                    Log.addEntry(logTag(), "ask location to: " + friend.mPublicIdentity.mNickname);
                    WebClientRequest webClientRequest =
                            new WebClientRequest(
                                mWebClientConnectionPool,
                                friend.mPublicIdentity.mHiddenServiceHostname,
                                Protocol.WEB_SERVER_VIRTUAL_PORT,
                                WebClientRequest.RequestType.GET,
                                Protocol.ASK_LOCATION_GET_REQUEST_PATH);
                    webClientRequest.makeRequest();
                } catch (Data.NotFoundError e) {
                    // Friend was deleted while task was enqueued. Ignore error.
                } catch (PloggyError e) {
                    try {
                        Log.addEntry(
                                logTag(),
                                "failed to ask location to: " +
                                    mData.getFriendByIdOrThrow(finalFriendId).mPublicIdentity.mNickname);
                    } catch (PloggyError e2) {
                        Log.addEntry(logTag(), "failed to ask location");
                    }
                } finally {
                    completedFriendTask(FriendTaskType.ASK_PULL, finalFriendId);
                }
            }
        };
    }

    private Runnable makePushToFriendTask(String friendId) {
        final String finalFriendId = friendId;
        return new Runnable() {
            @Override
            public void run() {
                try {
                    if (!mTorWrapper.isCircuitEstablished()) {
                        return;
                    }
                    Data.Friend friend = mData.getFriendById(finalFriendId);
                    while (true) {
                        Protocol.Payload payload = dequeueFriendPushPayload(finalFriendId);
                        if (payload == null) {
                            // *TODO* race condition when item enqueue before completedFriendTask is called; triggerFriendTask won't start a new task
                            break;
                        }
                        Log.addEntry(logTag(), "push to: " + friend.mPublicIdentity.mNickname);
                        WebClientRequest webClientRequest =
                                new WebClientRequest(
                                    mWebClientConnectionPool,
                                    friend.mPublicIdentity.mHiddenServiceHostname,
                                    Protocol.WEB_SERVER_VIRTUAL_PORT,
                                    WebClientRequest.RequestType.PUT,
                                    Protocol.PUSH_PUT_REQUEST_PATH).
                                        requestBody(Json.toJson(payload.mObject));
                        webClientRequest.makeRequest();
                        switch (payload.mType) {
                        case GROUP:
                            mData.confirmSentTo(friend.mId, (Protocol.Group)payload.mObject);
                            break;
                        case POST:
                            mData.confirmSentTo(friend.mId, (Protocol.Post)payload.mObject);
                            break;
                        default:
                            break;
                        }
                    }
                } catch (Data.NotFoundError e) {
                    // Friend was deleted while task was enqueued. Ignore error.
                } catch (PloggyError e) {
                    try {
                        Log.addEntry(
                                logTag(),
                                "failed to push to: " +
                                    mData.getFriendByIdOrThrow(finalFriendId).mPublicIdentity.mNickname);
                    } catch (PloggyError e2) {
                        Log.addEntry(logTag(), "failed to push");
                    }
                } finally {
                    completedFriendTask(FriendTaskType.PUSH_TO, finalFriendId);
                }
            }
        };
    }

    private Runnable makePullFromFriendTask(String friendId) {
        final String finalFriendId = friendId;
        return new Runnable() {
            @Override
            public void run() {
                try {
                    if (!mTorWrapper.isCircuitEstablished()) {
                        return;
                    }
                    Data.Friend friend = mData.getFriendById(finalFriendId);
                    Log.addEntry(logTag(), "pull from: " + friend.mPublicIdentity.mNickname);
                    // Pull twice. The first pull is to actually get data. The second pull
                    // is to explicitly acknowledge the received data via the last received
                    // sequence numbers passed in the second pull request. The second pull
                    // may receive additional data.
                    // The primary (first) pull is also the only one where we request a
                    // pull in the other direction from the peer.
                    for (int i = 0; i < 2; i++) {
                        final Protocol.PullRequest finalPullRequest = mData.getPullRequest(finalFriendId);
                        WebClientRequest.ResponseBodyHandler responseBodyHandler = new WebClientRequest.ResponseBodyHandler() {
                            @Override
                            public void consume(InputStream responseBodyInputStream) throws PloggyError {
                                Protocol.PullRequest pullRequest = finalPullRequest;
                                List<Protocol.Group> groups = new ArrayList<Protocol.Group>();
                                List<Protocol.Post> posts = new ArrayList<Protocol.Post>();
                                Json.PayloadIterator payloadIterator = new Json.PayloadIterator(responseBodyInputStream);
                                // TODO: polymorphism instead of cases-for-types?
                                for (Protocol.Payload payload : payloadIterator) {
                                    switch(payload.mType) {
                                    case GROUP:
                                        Protocol.Group group = (Protocol.Group)payload.mObject;
                                        Protocol.validateGroup(group);
                                        groups.add(group);
                                        break;
                                    case POST:
                                        Protocol.Post post = (Protocol.Post)payload.mObject;
                                        Protocol.validatePost(post);
                                        posts.add(post);
                                        break;
                                    default:
                                        break;
                                    }
                                    if (groups.size() + posts.size() >= Data.MAX_PULL_RESPONSE_TRANSACTION_OBJECT_COUNT) {
                                        mData.putPullResponse(finalFriendId, pullRequest, groups, posts);
                                        pullRequest = null;
                                        groups.clear();
                                        posts.clear();
                                    }
                                }
                                mData.putPullResponse(finalFriendId, pullRequest, groups, posts);
                            }
                        };
                        WebClientRequest webClientRequest =
                                new WebClientRequest(
                                    mWebClientConnectionPool,
                                    friend.mPublicIdentity.mHiddenServiceHostname,
                                    Protocol.WEB_SERVER_VIRTUAL_PORT,
                                    WebClientRequest.RequestType.PUT,
                                    Protocol.PULL_PUT_REQUEST_PATH).
                                        requestBody(Json.toJson(finalPullRequest)).
                                        responseBodyHandler(responseBodyHandler);
                        webClientRequest.makeRequest();
                    }
                } catch (Data.NotFoundError e) {
                    // Friend was deleted while task was enqueued. Ignore error.
                    // RemovedFriend should eventually cancel schedule.
                } catch (PloggyError e) {
                    try {
                        Log.addEntry(
                                logTag(),
                                "failed to pull from: " +
                                    mData.getFriendByIdOrThrow(finalFriendId).mPublicIdentity.mNickname);
                    } catch (PloggyError e2) {
                        Log.addEntry(logTag(), "failed to pull");
                    }
                } finally {
                    completedFriendTask(FriendTaskType.PULL_FROM, finalFriendId);
                }
            }
        };
    }

    private Runnable makeDownloadFromFriendTask(String friendId) {
        final String finalFriendId = friendId;
        return new Runnable() {
            @Override
            public void run() {
                try {
                    if (!mTorWrapper.isCircuitEstablished()) {
                        return;
                    }
                    if (getBooleanPreference(R.string.preferenceExchangeFilesWifiOnly)
                            && !Utils.isConnectedNetworkWifi(mContext)) {
                        // Will retry after next delay period
                        return;
                    }
                    Data.Friend friend = mData.getFriendById(finalFriendId);
                    while (true) {
                        Data.Download download = null;
                        try {
                            download = mData.getNextInProgressDownload(finalFriendId);
                        } catch (Data.NotFoundError e) {
                            break;
                        }
                        // TODO: there's a potential race condition between getDownloadedSize and
                        // openDownloadResourceForAppending; we may want to lock the file first.
                        // However: currently only one thread downloads files for a given friend.
                        long downloadedSize = Downloads.getDownloadedSize(download);
                        if (downloadedSize == download.mSize) {
                            // Already downloaded complete file, but may have failed to commit
                            // the COMPLETED state change. Skip the download.
                        } else {
                            Log.addEntry(logTag(), "download from: " + friend.mPublicIdentity.mNickname);
                            List<Pair<String, String>> requestParameters =
                                    Arrays.asList(new Pair<String, String>(Protocol.DOWNLOAD_GET_REQUEST_RESOURCE_ID_PARAMETER, download.mResourceId));
                            Pair<Long, Long> range = new Pair<Long, Long>(downloadedSize, (long)-1);
                            WebClientRequest webClientRequest =
                                    new WebClientRequest(
                                        mWebClientConnectionPool,
                                        friend.mPublicIdentity.mHiddenServiceHostname,
                                        Protocol.WEB_SERVER_VIRTUAL_PORT,
                                        WebClientRequest.RequestType.GET,
                                        Protocol.DOWNLOAD_GET_REQUEST_PATH).
                                            requestParameters(requestParameters).
                                            rangeHeader(range).
                                            responseBodyOutputStream(Downloads.openDownloadResourceForAppending(download));
                            webClientRequest.makeRequest();
                        }
                        mData.updateDownloadState(friend.mId, download.mResourceId, Data.Download.State.COMPLETE);
                        // TODO: WebClient post to event bus for download progress (replacing timer-based refreshes...)
                        // TODO: 404/403: denied by peer? -- change Download state to reflect this and don't retry (e.g., new state: CANCELLED)
                        // TODO: update some last received timestamp?
                    }
                } catch (Data.NotFoundError e) {
                    // Friend was deleted while task was enqueued. Ignore error.
                    // RemovedFriend should eventually cancel schedule.
                } catch (PloggyError e) {
                    try {
                        Log.addEntry(
                                logTag(),
                                "failed to download from: " +
                                    mData.getFriendByIdOrThrow(finalFriendId).mPublicIdentity.mNickname);
                    } catch (PloggyError e2) {
                        Log.addEntry(logTag(), "failed to download status");
                    }
                } finally {
                    completedFriendTask(FriendTaskType.DOWNLOAD_FROM, finalFriendId);
                }
            }
        };
    }

    // Note: WebServer callbacks not synchronized
    @Override
    public String getFriendNicknameByCertificate(String friendCertificate) throws PloggyError {
        Data.Friend friend = mData.getFriendByCertificateOrThrow(friendCertificate);
        return friend.mPublicIdentity.mNickname;
    }

    // Note: WebServer callbacks not synchronized
    @Override
    public void updateFriendSent(String friendCertificate, Date lastSentToTimestamp, long additionalBytesSentTo)
            throws PloggyError  {
        Data.Friend friend = mData.getFriendByCertificateOrThrow(friendCertificate);
        mData.updateFriendSentOrThrow(friend.mId, lastSentToTimestamp, additionalBytesSentTo);
    }

    // Note: WebServer callbacks not synchronized
    @Override
    public void updateFriendReceived(String friendCertificate, Date lastReceivedFromTimestamp, long additionalBytesReceivedFrom)
            throws PloggyError {
        Data.Friend friend = mData.getFriendByCertificateOrThrow(friendCertificate);
        mData.updateFriendReceivedOrThrow(friend.mId, lastReceivedFromTimestamp, additionalBytesReceivedFrom);
    }

    // Note: WebServer callbacks not synchronized
    @Override
    public void handleAskPullRequest(String friendCertificate) throws PloggyError {
        try {
            Data.Friend friend = mData.getFriendByCertificate(friendCertificate);
            triggerFriendTask(FriendTaskType.PULL_FROM, friend.mId);
            Log.addEntry(logTag(), "served ask pull request for " + friend.mPublicIdentity.mNickname);
        } catch (Data.NotFoundError e) {
            throw new PloggyError(logTag(), "failed to handle ask pull request: friend not found");
        }
    }

    // Note: WebServer callbacks not synchronized
    @Override
    public void handleAskLocationRequest(String friendCertificate) throws PloggyError {
        try {
            Data.Friend friend = mData.getFriendByCertificate(friendCertificate);
            if (!currentlySharingLocation()) {
                throw new PloggyError(logTag(), "rejected ask location request for " + friend.mPublicIdentity.mNickname);
            }
            addFriendToReceiveLocation(friend.mId);
            Log.addEntry(logTag(), "served ask location request for " + friend.mPublicIdentity.mNickname);
        } catch (Data.NotFoundError e) {
            throw new PloggyError(logTag(), "failed to handle ask location request: friend not found");
        }
    }

    // Note: WebServer callbacks not synchronized
    @Override
    public void handlePushRequest(String friendCertificate, String requestBody) throws PloggyError  {
        try {
            Data.Friend friend = mData.getFriendByCertificate(friendCertificate);
            // TODO: stream requestBody instead of loading entirely into memory
            Json.PayloadIterator payloadIterator = new Json.PayloadIterator(requestBody);
            Set<String> pullFromFriendIds = new HashSet<String>();
            for (Protocol.Payload payload : payloadIterator) {
                switch(payload.mType) {
                case GROUP:
                    Protocol.Group group = (Protocol.Group)payload.mObject;
                    Protocol.validateGroup(group);
                    mData.putPushedGroup(friend.mId, group);
                    // In case self was added to existing group, need to now get the posts
                    // *TODO* should this instead be a push to only new members?
                    for (Identity.PublicIdentity member : group.mMembers) {
                        try {
                            mData.getFriendById(member.mId);
                            pullFromFriendIds.add(member.mId);
                        } catch (Data.NotFoundError e) {
                            // This member is not a friend
                        }
                    }
                    break;
                case LOCATION:
                    Protocol.Location location = (Protocol.Location)payload.mObject;
                    Protocol.validateLocation(location);
                    mData.putPushedLocation(friend.mId, location);
                    break;
                case POST:
                    Protocol.Post post = (Protocol.Post)payload.mObject;
                    Protocol.validatePost(post);
                    if (mData.putPushedPost(friend.mId, post)) {
                        pullFromFriendIds.add(friend.mId);
                    }
                    break;
                default:
                    break;
                }
            }
            for (String friendId : pullFromFriendIds) {
                triggerFriendTask(FriendTaskType.PULL_FROM, friendId);
            }
            // *TODO* log too noisy?
            Log.addEntry(logTag(), "served push request for " + friend.mPublicIdentity.mNickname);
        } catch (Data.NotFoundError e) {
            // *TODO* use XYZOrThrow instead?
            throw new PloggyError(logTag(), "failed to handle push request: friend not found");
        }
    }

    // Note: WebServer callbacks not synchronized
    @Override
    public WebServer.RequestHandler.PullResponse handlePullRequest(String friendCertificate, String requestBody) throws PloggyError {
        try {
            Data.Friend friend = mData.getFriendByCertificate(friendCertificate);
            Protocol.PullRequest pullRequest = Json.fromJson(requestBody, Protocol.PullRequest.class);
            Protocol.validatePullRequest(pullRequest);
            mData.confirmSentTo(friend.mId, pullRequest);
            Data.PullResponseIterator pullResponseIterator = mData.getPullResponse(friend.mId, pullRequest);
            Log.addEntry(logTag(), "served pull request for " + friend.mPublicIdentity.mNickname);
            return new WebServer.RequestHandler.PullResponse(
                    new Utils.StringIteratorInputStream(pullResponseIterator));
        } catch (Data.NotFoundError e) {
            // *TODO* use XYZOrThrow instead?
            throw new PloggyError(logTag(), "failed to handle pull status request: friend not found");
        }
    }

    // Note: WebServer callbacks not synchronized
    @Override
    public WebServer.RequestHandler.DownloadResponse handleDownloadRequest(
            String friendCertificate, String resourceId, Pair<Long, Long> range) throws PloggyError  {
        try {
            Data.Friend friend = mData.getFriendByCertificate(friendCertificate);
            Data.LocalResource localResource = mData.getLocalResourceForDownload(friend.mId, resourceId);
            // Note: don't check availability until after input validation
            if (getBooleanPreference(R.string.preferenceExchangeFilesWifiOnly)
                    && !Utils.isConnectedNetworkWifi(mContext)) {
                // Download service not available
                return new DownloadResponse(false, null, null);
            }
            InputStream inputStream = Resources.openLocalResourceForReading(localResource, range);
            Log.addEntry(logTag(), "served download request for " + friend.mPublicIdentity.mNickname);
            return new DownloadResponse(true, localResource.mMimeType, inputStream);
        } catch (Data.NotFoundError e) {
            // *TODO* use XYZOrThrow instead?
            throw new PloggyError(logTag(), "failed to handle download request: friend or resource not found");
        }
    }

    public synchronized Context getContext() {
        return mContext;
    }

    public synchronized boolean getBooleanPreference(int keyResID) throws PloggyError {
        String key = mContext.getString(keyResID);
        // Defaults which are "false" are not present in the preferences file
        // if (!mSharedPreferences.contains(key)) {...}
        // TODO: this is ambiguous: there's now no test for failure to initialize defaults
        return mSharedPreferences.getBoolean(key, false);
    }

    public synchronized int getIntPreference(int keyResID) throws PloggyError {
        String key = mContext.getString(keyResID);
        if (!mSharedPreferences.contains(key)) {
            throw new PloggyError(logTag(), "missing preference default: " + key);
        }
        return mSharedPreferences.getInt(key, 0);
    }

    public synchronized boolean currentlySharingLocation() throws PloggyError {
        if (!getBooleanPreference(R.string.preferenceAutomaticLocationSharing)) {
            return false;
        }

        Calendar now = Calendar.getInstance();

        if (getBooleanPreference(R.string.preferenceLimitLocationSharingTime)) {
            int currentHour = now.get(Calendar.HOUR_OF_DAY);
            int currentMinute = now.get(Calendar.MINUTE);

            String sharingTimeNotBefore = mSharedPreferences.getString(
                    mContext.getString(R.string.preferenceLimitLocationSharingTimeNotBefore), "");
            int notBeforeHour = TimePickerPreference.getHour(sharingTimeNotBefore);
            int notBeforeMinute = TimePickerPreference.getMinute(sharingTimeNotBefore);
            String sharingTimeNotAfter = mSharedPreferences.getString(
                    mContext.getString(R.string.preferenceLimitLocationSharingTimeNotAfter), "");
            int notAfterHour = TimePickerPreference.getHour(sharingTimeNotAfter);
            int notAfterMinute = TimePickerPreference.getMinute(sharingTimeNotAfter);

            if ((currentHour < notBeforeHour) ||
                (currentHour == notBeforeHour && currentMinute < notBeforeMinute) ||
                (currentHour > notAfterHour) ||
                (currentHour == notAfterHour && currentMinute > notAfterMinute)) {
                return false;
            }
        }

        // Map current Calendar.DAY_OF_WEEK (1..7) to preference's SUNDAY..SATURDAY symbols
        assert(Calendar.SUNDAY == 1 && Calendar.SATURDAY == 7);
        String[] weekdays = mContext.getResources().getStringArray(R.array.weekdays);
        String currentWeekday = weekdays[now.get(Calendar.DAY_OF_WEEK) - 1];

        Set<String> sharingDays = mSharedPreferences.getStringSet(
                mContext.getString(R.string.preferenceLimitLocationSharingDay),
                new HashSet<String>());

        if (!sharingDays.contains(currentWeekday)) {
            return false;
        }

        return true;
    }
}
