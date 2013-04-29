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
package com.twolinessoftware.android.framework.service.comms.gpx;

import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import android.util.Log;

public class GpxTrackPoint implements Serializable {

    private static final long serialVersionUID = -4894963006110633397L;
    private double ele;
    private long time;
    private String fix;
    private String sat;
    private double lat;
    private double lon;
    private double course;
    private double speed;
    private final String preciseTemplate = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
    private final String impreciseTemplate = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    public double getCourse() {
        return course;
    }

    public void setCourse(double course) {
        this.course = course;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public double getEle() {
        return ele;
    }

    public void setEle(double ele) {
        this.ele = ele;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long newTime) {
        time = newTime;
    }

    public void setTime(String timeString) {
        SimpleDateFormat format = null;
        if (timeString.length() < (preciseTemplate.length() - 4)) {
            format = new SimpleDateFormat(impreciseTemplate);
        } else {
            format = new SimpleDateFormat(preciseTemplate);
        }
        try {
            time = format.parse(timeString).getTime();
        } catch (ParseException e) {
            Log.e("GpxTrackPoint", e.getMessage());
            time = System.currentTimeMillis();
        }
    }

    public String getFix() {
        return fix;
    }

    public void setFix(String fix) {
        this.fix = fix;
    }

    public String getSat() {
        return sat;
    }

    public void setSat(String sat) {
        this.sat = sat;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }
}
