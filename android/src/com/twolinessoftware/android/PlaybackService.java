/*
 * Copyright (c) 2011 2linessoftware.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twolinessoftware.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.twolinessoftware.android.framework.service.comms.gpx.GpxPullParser;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxPullParserListener;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxTrackPoint;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxTrackSegments;

public class PlaybackService extends Service implements GpxPullParserListener {

    private Integer workerIndex = 0;
    private static Timer ticker = new Timer();

    private NotificationManager mNM;

    private static final String LOGTAG = PlaybackService.class.getSimpleName();

    private static final int NOTIFICATION = 1;

    public static final long UPDATE_LOCATION_WAIT_TIME = 1000;

    protected List<GpxTrackPoint> pointList = new ArrayList<GpxTrackPoint>();

    public static final boolean CONTINUOUS = true;

    public static final int RUNNING = 0;
    public static final int STOPPED = 1;

    private static final String PROVIDER_NAME = LocationManager.GPS_PROVIDER;
    private static final int SAMPLES_IN_MINUTES = 60;

    private final IPlaybackService.Stub mBinder = new IPlaybackService.Stub() {

        @Override
        public void startService(String file) throws RemoteException {

            broadcastStateChange(PlaybackService.RUNNING);

            loadGpxFile(file);

            workerIndex = 0;
            PlaybackService.ticker.scheduleAtFixedRate(new tickerTask(), 1000, 1000);
        }

        @Override
        public void stopService() throws RemoteException {
            PlaybackService.ticker.cancel();

            mLocationManager.removeTestProvider(PlaybackService.PROVIDER_NAME);

            broadcastStateChange(PlaybackService.STOPPED);

            cancelExistingTaskIfNecessary();

            onGpsPlaybackStopped();

            stopSelf();
        }

        @Override
        public int getState() throws RemoteException {
            return state;
        }

        @Override
        public void jump(int i) throws RemoteException {
            int count = pointList.size();
            int newIndex = ((workerIndex + (PlaybackService.SAMPLES_IN_MINUTES * i)) % count);
            if (newIndex < 0) {
                newIndex = count + newIndex;
            }
            setWorkerIndex(newIndex);
        }
    };

    private LocationManager mLocationManager;

    private int state;

    private ReadFileTask task;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {

        mNM = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        broadcastStateChange(PlaybackService.STOPPED);

        setupTestProvider();
    }

    class tickerTask extends TimerTask {
        private static final float FAKE_ACCURACY = 5;

        private void sendLocation(final GpxTrackPoint point) {
            Location loc = new Location(PlaybackService.PROVIDER_NAME);
            loc.setLatitude(point.getLat());
            loc.setLongitude(point.getLon());
            loc.setBearing((float) point.getCourse());
            loc.setSpeed((float) point.getSpeed());
            loc.setTime(System.currentTimeMillis());
            loc.setAccuracy(tickerTask.FAKE_ACCURACY);
            Log.d(PlaybackService.LOGTAG, PlaybackService.PROVIDER_NAME + ": " + point.getLat() + ", " + point.getLon());
            mLocationManager.setTestProviderLocation(PlaybackService.PROVIDER_NAME, loc);
        }

        @Override
        public void run() {
            if (!pointList.isEmpty()) {
                sendLocation(pointList.get(workerIndex));
                broadcastProgress((100 * workerIndex) / pointList.size());
                setWorkerIndex(workerIndex + 1);
            }
        }
    }

    public void setWorkerIndex(int newIndex) {
        synchronized (workerIndex) {
            workerIndex = newIndex;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(PlaybackService.LOGTAG, "Starting Playback Service");

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        PlaybackService.ticker.cancel();
        Log.d(PlaybackService.LOGTAG, "Stopping Playback Service");
    }

    private void cancelExistingTaskIfNecessary() {
        if (task != null) {
            try {
                task.cancel(true);
            } catch (Exception e) {
                Log.e(PlaybackService.LOGTAG, "Unable to cancel playback task. May already be stopped");
            }
        }
    }

    private void loadGpxFile(String file) {
        if (file != null) {

            broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadStarted);

            cancelExistingTaskIfNecessary();

            task = new ReadFileTask(file);
            task.execute();

            // Display a notification about us starting. We put an icon in the
            // status bar.
            showNotification();
        }

    }

    private void queueGpxPositions(String filename) {
        GpxPullParser parser = new GpxPullParser(this);
        File f = new File(filename);
        FileInputStream fileIS = null;
        try {
            fileIS = new FileInputStream(f);
        } catch (FileNotFoundException e) {
            Log.e(PlaybackService.LOGTAG, filename + " not found!");
            showNotification(filename + " not found!");
        }
        parser.parse(fileIS);
    }

    private void onGpsPlaybackStopped() {

        broadcastStateChange(PlaybackService.STOPPED);

        // Cancel the persistent notification.
        mNM.cancel(PlaybackService.NOTIFICATION);

        disableGpsProvider();

    }

    private void disableGpsProvider() {

        if (mLocationManager.getProvider(PlaybackService.PROVIDER_NAME) != null) {
            mLocationManager.removeTestProvider(PlaybackService.PROVIDER_NAME);
        }
    }

    private void setupTestProvider() {
        try {
            mLocationManager.removeTestProvider(PlaybackService.PROVIDER_NAME);
            mLocationManager.addTestProvider(PlaybackService.PROVIDER_NAME, true, // requiresNetwork,
                    false, // requiresSatellite,
                    true, // requiresCell,
                    false, // hasMonetaryCost,
                    false, // supportsAltitude,
                    false, // supportsSpeed,
                    false, // supportsBearing,
                    Criteria.POWER_MEDIUM, // powerRequirement
                    Criteria.ACCURACY_FINE); // accuracy
        } catch (SecurityException e) {
            Log.e(PlaybackService.LOGTAG,
                    "Manifest.ACCESS_MOCK_LOCATION or Settings.Secure.ALLOW_MOCK_LOCATION not enabled:" + e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.e(PlaybackService.LOGTAG, "No provider with the given name exists: " + e.getMessage());
        }
    }

    // Default version.
    private void showNotification() {
        showNotification("GPX Playback Running");
    }

    /**
     * Show a notification while this service is running.
     */
    private void showNotification(String text) {
        // In this sample, we'll use the same text for the ticker and the
        // expanded notification

        // The PendingIntent to launch our activity if the user selects this
        // notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);

        // Set the icon, scrolling text and timestamp.
        Notification notification = new NotificationCompat.Builder(this).setContentText(text).setSmallIcon(R.drawable.ic_playback_running)
                .setWhen(System.currentTimeMillis()).setContentTitle("GPX Playback Manager").setContentIntent(contentIntent).build();

        // Send the notification.
        mNM.notify(PlaybackService.NOTIFICATION, notification);
    }

    @Override
    public void onGpxError(String message) {
        Log.e(PlaybackService.LOGTAG, message);
        broadcastError(message);
    }

    @Override
    public void onGpxPoint(GpxTrackPoint item) {
        synchronized (pointList) {
            pointList.add(item);
        }
    }

    @Override
    public void onGpxStart() {
        Log.i(PlaybackService.LOGTAG, "GPX started.");
    }

    @Override
    public void onGpxEnd() {
        Log.i(PlaybackService.LOGTAG, "GPX ended with " + pointList.size() + " points parsed.");
    }

    private void broadcastProgress(int pct) {
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, GpsPlaybackBroadcastReceiver.Status.playbackProgress.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE, pct);
        sendBroadcast(i);
    }

    private void broadcastStatus(GpsPlaybackBroadcastReceiver.Status status) {
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, status.toString());
        sendBroadcast(i);
    }

    private void broadcastError(String message) {
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, GpsPlaybackBroadcastReceiver.Status.fileError.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE, state);
        sendBroadcast(i);
    }

    private void broadcastStateChange(int newState) {
        state = newState;
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, GpsPlaybackBroadcastReceiver.Status.statusChange.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE, state);
        sendBroadcast(i);
    }

    private class ReadFileTask extends AsyncTask<Void, Integer, Void> {

        private final String file;

        public ReadFileTask(String file) {
            super();
            this.file = file;
        }

        @Override
        protected void onPostExecute(Void result) {
            broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadfinished);
        }

        @Override
        protected Void doInBackground(Void... arg0) {
            publishProgress(1);
            queueGpxPositions(file);
            return null;
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            switch (progress[0]) {
                case 1:
                    broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadfinished);
                    break;
            }
        }
    }

    @Override
    public void onGpxRoute(GpxTrackSegments items) {
        Log.i(PlaybackService.LOGTAG, "Got " + items.getTrackSegments().size() + " track segment items.");
    }
}
