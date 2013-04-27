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

import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import com.twolinessoftware.android.framework.service.comms.Worker;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxTrackPoint;

public class SendLocationWorker extends Worker {

    private static final float FAKE_ACCURACY = 5;
    private final GpxTrackPoint point;
    private final String providerName;
    private final LocationManager mLocationManager;

    private long sendTime;

    public long getSendTime() {
        return sendTime;
    }

    public void setSendTime(long sendTime) {
        this.sendTime = sendTime;
    }

    public SendLocationWorker(LocationManager mLocationManager, GpxTrackPoint point, String providerName, long localSendTime) {
        super();
        this.point = point;
        this.providerName = providerName;
        this.mLocationManager = mLocationManager;
        sendTime = localSendTime;
    }

    @Override
    public void run() {
        sendLocation(point);
    }

    private void sendLocation(final GpxTrackPoint point) {
        Location loc = new Location(providerName);
        loc.setLatitude(point.getLat());
        loc.setLongitude(point.getLon());
        loc.setBearing((float) point.getCourse());
        loc.setSpeed((float) point.getSpeed());
        loc.setTime(sendTime);
        loc.setAccuracy(FAKE_ACCURACY);
        Log.d("SendLocation", "Sending update for " + providerName);
        mLocationManager.setTestProviderLocation(providerName, loc);
    }

}
