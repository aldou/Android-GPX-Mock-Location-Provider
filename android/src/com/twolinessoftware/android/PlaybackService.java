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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.sf.marineapi.nmea.event.SentenceEvent;
import net.sf.marineapi.nmea.event.SentenceListener;
import net.sf.marineapi.nmea.sentence.Sentence;
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

import com.twolinessoftware.android.framework.service.comms.gps.GpxPullParser;
import com.twolinessoftware.android.framework.service.comms.gps.GpxPullParserListener;
import com.twolinessoftware.android.framework.service.comms.gps.GpxTrackPoint;
import com.twolinessoftware.android.framework.service.comms.gps.GpxTrackSegments;
import com.twolinessoftware.android.framework.service.comms.gps.NmeaParser;

public class PlaybackService extends Service implements GpxPullParserListener, SentenceListener {

    /**
     * The list of GPS points to use in mocking location updates.
     */
    protected List<GpxTrackPoint> pointList = new ArrayList<GpxTrackPoint>();

    /**
     * Currently active point in pointList.
     */
    private Integer workerIndex = 0;

    private NotificationManager mNM;

    /**
     * A bunch of constants.
     */
    private static final String LOGTAG = PlaybackService.class.getSimpleName();
    private static final int NOTIFICATION = 1;
    public static final long UPDATE_LOCATION_WAIT_TIME = 1000;
    public static final boolean CONTINUOUS = true;
    public static final int RUNNING = 0;
    public static final int STOPPED = 1;
    private static final String PROVIDER_NAME = LocationManager.GPS_PROVIDER;
    private static final int SAMPLES_IN_MINUTES = 60;

    /**
     * Member variables used in scheduling location updates.
     */
    private final ScheduledExecutorService scheduleTaskExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> tickerHandle = null;

    /**
     * Location manager is used to broadcast mock location updates.
     */
    private LocationManager mLocationManager;

    /**
     * Playback service state.
     */
    private int state;

    /**
     * File reader.
     */
    private ReadFileTask task;

    /**
     * When loadGpxFile is called, this variable is used to figure out if
     * the active file has changed thus requiring a reset of workerIndex.
     */
    private String previousFilename = "";

    /**
     * Service stub.
     */
    private final IPlaybackService.Stub mBinder = new IPlaybackService.Stub() {

        /**
         * Start the service.
         *
         * @param file
         *            The file to load.
         * @throws RemoteException
         */
        @Override
        public void startService(String file) throws RemoteException {
            broadcastStateChange(PlaybackService.RUNNING);
            loadGpxFile(file);
            tickerHandle = scheduleTaskExecutor.scheduleAtFixedRate(new TickerTask(), 1, 1, TimeUnit.SECONDS);
        }

        /**
         * Stop the service.
         *
         * @throws RemoteException
         */
        @Override
        public void stopService() throws RemoteException {
            tickerHandle.cancel(true);

            broadcastStateChange(PlaybackService.STOPPED);

            cancelExistingTaskIfNecessary();

            onGpsPlaybackStopped();

            stopSelf();
        }

        @Override
        public int getState() throws RemoteException {
            return state;
        }

        /**
         * Interface for jumping around in pointList.
         *
         * @param i
         *            The amount of minutes to jump ahead/behind.
         * @throws RemoteException
         */
        @Override
        public void jump(int i) throws RemoteException {
            setWorkerIndex(workerIndex + (PlaybackService.SAMPLES_IN_MINUTES * i));
            Log.i(PlaybackService.LOGTAG, "@" + workerIndex + "/" + pointList.size());
            broadcastProgress();
        }
    };

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

    class TickerTask implements Runnable {
        private static final float FAKE_ACCURACY = 5;

        private void sendLocation(final GpxTrackPoint point) {
            Location loc = new Location(PlaybackService.PROVIDER_NAME);
            loc.setLatitude(point.getLat());
            loc.setLongitude(point.getLon());
            loc.setBearing((float) point.getCourse());
            loc.setSpeed((float) point.getSpeed());
            loc.setTime(System.currentTimeMillis());
            loc.setAccuracy(TickerTask.FAKE_ACCURACY);
            Log.d(PlaybackService.LOGTAG, PlaybackService.PROVIDER_NAME + ": " + point.getLat() + ", " + point.getLon());
            try {
                mLocationManager.setTestProviderLocation(PlaybackService.PROVIDER_NAME, loc);
            } catch (Exception e) {
                Log.e(PlaybackService.LOGTAG, "ARGH! " + e.getMessage());
            }
        }

        @Override
        public void run() {
            if (!pointList.isEmpty()) {
                sendLocation(pointList.get(workerIndex));
                broadcastProgress();
                setWorkerIndex(workerIndex + 1);
            }
        }
    }

    public void setWorkerIndex(int newIndex) {
        int count = pointList.size();
        if (state == PlaybackService.STOPPED) {
            // In stopped-state we won't wrap.
            if (newIndex < 0) {
                newIndex = 0;
            }
            if (newIndex > (count - 1)) {
                newIndex = count - 1;
            }
        } else {
            // Wrap is active only in playback-state.
            newIndex = newIndex % count;
            if (newIndex < 0) {
                newIndex = count + newIndex;
            }
        }
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
        if (tickerHandle != null) {
            tickerHandle.cancel(true);
        }
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
            if (file.equals(previousFilename)) {
                // File hasn't changed, bail out.
                showNotification();
                return;
            }

            // File has changed, clear workerIndex, ...
            workerIndex = 0;
            // store the new file, ...
            previousFilename = file;
            // and clear old points.
            pointList.clear();

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
        File f = new File(filename);
        FileInputStream fileIS = null;
        try {
            fileIS = new FileInputStream(f);
        } catch (FileNotFoundException e) {
            Log.e(PlaybackService.LOGTAG, filename + " not found!");
            showNotification(filename + " not found!");
        }
        if (!filename.toLowerCase().endsWith(".nmea")) {
            GpxPullParser parser = new GpxPullParser(this);
            parser.parse(fileIS);
        } else {
            NmeaParser nmeaParser = new NmeaParser(this);
            nmeaParser.parse(fileIS);
        }
    }

    private void onGpsPlaybackStopped() {

        broadcastStateChange(PlaybackService.STOPPED);

        // Cancel the persistent notification.
        mNM.cancel(PlaybackService.NOTIFICATION);

        disableGpsProvider();

    }

    private void disableGpsProvider() {
        if (mLocationManager.getProvider(PlaybackService.PROVIDER_NAME) != null) {
            try {
                mLocationManager.removeTestProvider(PlaybackService.PROVIDER_NAME);
            } catch (SecurityException e) {
                Log.e(PlaybackService.LOGTAG,
                        "Manifest.ACCESS_MOCK_LOCATION or Settings.Secure.ALLOW_MOCK_LOCATION not enabled:" + e.getMessage());
            } catch (IllegalArgumentException e) {
                Log.e(PlaybackService.LOGTAG, "No provider with the given name exists: " + e.getMessage());
            }
        }
    }

    private void setupTestProvider() {
        try {
            mLocationManager.removeTestProvider(PlaybackService.PROVIDER_NAME);
        } catch (SecurityException e) {
            Log.e(PlaybackService.LOGTAG,
                    "Manifest.ACCESS_MOCK_LOCATION or Settings.Secure.ALLOW_MOCK_LOCATION not enabled:" + e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.e(PlaybackService.LOGTAG, "Not fatal: provider with the given name does not exist: " + e.getMessage());
        }
        try {
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
        showNotification("GPS Playback Running");
    }

    /**
     * Show a notification while this service is running.
     */
    private void showNotification(String text) {
        // In this sample, we'll use the same text for the ticker and the
        // expanded notification

        // The PendingIntent to launch our activity if the user selects this
        // notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, GPSPlaybackActivity.class), 0);

        // Set the icon, scrolling text and timestamp.
        Notification notification = new NotificationCompat.Builder(this).setContentText(text).setSmallIcon(R.drawable.ic_playback_running)
                .setWhen(System.currentTimeMillis()).setContentTitle("GPS Playback Manager").setContentIntent(contentIntent).build();

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
        broadcastProgress();
    }

    @Override
    public void onGpxStart() {
        Log.i(PlaybackService.LOGTAG, "GPS parsing started.");
    }

    @Override
    public void onGpxEnd() {
        Log.i(PlaybackService.LOGTAG, "GPS parsing ended with " + pointList.size() + " points parsed.");
    }

    private void broadcastProgress() {
        int pct = 1 + ((100 * workerIndex) / pointList.size());
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

    @Override
    public void readingPaused() {
        Log.i(PlaybackService.LOGTAG, "NMEA reading paused.");
    }

    @Override
    public void readingStarted() {
        Log.i(PlaybackService.LOGTAG, "NMEA reading started.");
    }

    @Override
    public void readingStopped() {
        Log.i(PlaybackService.LOGTAG, "NMEA reading stopped.");
    }

    @Override
    public void sentenceRead(SentenceEvent event) {
        Sentence sentence = event.getSentence();
        Log.i(PlaybackService.LOGTAG, "Got positionevent: " + sentence.toString());
    }
}
