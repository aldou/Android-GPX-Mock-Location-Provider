/*
 * Copyright (c) 2011 2linessoftware.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twolinessoftware.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.twolinessoftware.android.framework.service.comms.gpx.GpxSaxParser;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxSaxParserListener;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxTrackPoint;
import com.twolinessoftware.android.framework.util.Logger;

public class PlaybackService extends Service implements GpxSaxParserListener {

    private NotificationManager mNM;

    private static final String LOG = PlaybackService.class.getSimpleName();

    private static final int NOTIFICATION = 1;

    public static final long UPDATE_LOCATION_WAIT_TIME = 1000;

    private final ArrayList<GpxTrackPoint> pointList = new ArrayList<GpxTrackPoint>();

    public static final boolean CONTINUOUS = true;

    public static final int RUNNING = 0;
    public static final int STOPPED = 1;

    private static final String PROVIDER_NAME = LocationManager.GPS_PROVIDER;

    private final IPlaybackService.Stub mBinder = new IPlaybackService.Stub() {


        @Override
        public void startService(String file) throws RemoteException {

            broadcastStateChange(PlaybackService.RUNNING);

            loadGpxFile(file);

        }

        @Override
        public void stopService() throws RemoteException {
            mLocationManager.removeTestProvider(PlaybackService.PROVIDER_NAME);

            queue.reset();

            broadcastStateChange(PlaybackService.STOPPED);

            cancelExistingTaskIfNecessary();

            onGpsPlaybackStopped();

            stopSelf();
        }

        @Override
        public int getState() throws RemoteException {
            return state;
        }

    };

    private LocationManager mLocationManager;

    private long startTimeOffset;

    private long firstGpsTime;

    private int state;

    private SendLocationWorkerQueue queue;

    private ReadFileTask task;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {

        mNM = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);

        queue = new SendLocationWorkerQueue();
        queue.start();

        broadcastStateChange(PlaybackService.STOPPED);

        setupTestProvider();

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(PlaybackService.LOG,"Starting Playback Service");

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(PlaybackService.LOG,"Stopping Playback Service");

    }

    private void cancelExistingTaskIfNecessary(){
        if(task != null){
            try{
                task.cancel(true);
            }catch(Exception e){
                Log.e(PlaybackService.LOG, "Unable to cancel playback task. May already be stopped");
            }
        }
    }

    private void loadGpxFile(String file){
        if(file != null){

            broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadStarted);

            cancelExistingTaskIfNecessary();

            task = new ReadFileTask(file);
            task.execute();

            // Display a notification about us starting.  We put an icon in the status bar.
            showNotification();
        }

    }


    private void queueGpxPositions(String xml){
        GpxSaxParser parser = new GpxSaxParser(this);
        parser.parse(xml);
    }

    private void onGpsPlaybackStopped(){

        broadcastStateChange(PlaybackService.STOPPED);

        // Cancel the persistent notification.
        mNM.cancel(PlaybackService.NOTIFICATION);

        disableGpsProvider();

    }

    private void disableGpsProvider(){

        if (mLocationManager.getProvider(PlaybackService.PROVIDER_NAME) != null) {
            mLocationManager.removeTestProvider(PlaybackService.PROVIDER_NAME);
        }
    }

    private void setupTestProvider(){
        mLocationManager.addTestProvider(PlaybackService.PROVIDER_NAME, true, //requiresNetwork,
                false, // requiresSatellite,
                true, // requiresCell,
                false, // hasMonetaryCost,
                false, // supportsAltitude,
                false, // supportsSpeed, s
                false, // upportsBearing,
                Criteria.POWER_MEDIUM, // powerRequirement
                Criteria.ACCURACY_FINE); // accuracy
    }


    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = "GPX Playback Running";

        // Set the icon, scrolling text and timestamp
        Notification notification = new Notification(R.drawable.ic_playback_running, text,
                System.currentTimeMillis());

        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        // Set the info for the views that show in the notification panel.
        notification.setLatestEventInfo(this, "GPX Playback Manager",text, contentIntent);

        // Send the notification.
        mNM.notify(PlaybackService.NOTIFICATION, notification);
    }

    private String loadFile(String file){

        try{
            File f = new File(file);

            FileInputStream fileIS = new FileInputStream(f);

            BufferedReader buf = new BufferedReader(new InputStreamReader(fileIS));

            String readString = new String();

            StringBuffer xml = new StringBuffer();
            while((readString = buf.readLine())!= null){
                xml.append(readString);
            }

            Logger.d(PlaybackService.LOG,"Finished reading in file");

            buf.close();
            return xml.toString();

        } catch (Exception e) {
            broadcastError("Error in the GPX file, unable to read it");
        }

        return null;
    }


    @Override
    public void onGpxError(String message) {
        broadcastError(message);
    }


    @Override
    public void onGpxPoint(GpxTrackPoint item) {

        long delay = System.currentTimeMillis() + 2000; // ms until the point should be displayed

        long gpsPointTime = 0;

        // Calculate the delay
        if(item.getTime() != null){
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

            try {
                Date gpsDate = format.parse(item.getTime());
                gpsPointTime = gpsDate.getTime();
            } catch (ParseException e) {
                // Log.e(PlaybackService.LOG, "Unable to parse time:"+item.getTime());
                gpsPointTime = System.currentTimeMillis();
            }

            if(firstGpsTime == 0) {
                firstGpsTime = gpsPointTime;
            }


            if(startTimeOffset == 0) {
                startTimeOffset = System.currentTimeMillis();
            }


            delay = (gpsPointTime - firstGpsTime) + startTimeOffset;
        }


        pointList.add(item);
        if(state == PlaybackService.RUNNING){
            if(delay > 0){
                Log.d(PlaybackService.LOG,"Sending Point in:"+(delay-System.currentTimeMillis())+"ms");

                SendLocationWorker worker = new SendLocationWorker(mLocationManager,item,PlaybackService.PROVIDER_NAME, delay);
                queue.addToQueue(worker);
            }else{
                Log.e(PlaybackService.LOG,"Invalid Time at Point:"+gpsPointTime+" delay from current time:"+delay);
            }
        }

    }

    @Override
    public void onGpxStart() {
        // Start Parsing
    }

    @Override
    public void onGpxEnd() {
        // End Parsing
    }

    private void broadcastStatus(GpsPlaybackBroadcastReceiver.Status status){
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS,status.toString());
        sendBroadcast(i);
    }

    private void broadcastError(String message){
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS,GpsPlaybackBroadcastReceiver.Status.fileError.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE,state);
        sendBroadcast(i);
    }

    private void broadcastStateChange(int newState){
        state = newState;
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS,GpsPlaybackBroadcastReceiver.Status.statusChange.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE,state);
        sendBroadcast(i);
    }

    private class ReadFileTask extends AsyncTask<Void,Integer,Void>{

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

            // Reset the existing values
            firstGpsTime = 0;

            startTimeOffset = 0;

            String xml = loadFile(file);

            publishProgress(1);

            queueGpxPositions(xml);

            return null;
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            switch(progress[0]){
                case 1:
                    broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadfinished);
                    break;
            }

        }




    }



}