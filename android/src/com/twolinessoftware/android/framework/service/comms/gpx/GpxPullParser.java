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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.util.Log;

import com.twolinessoftware.android.framework.service.comms.Parser;

public class GpxPullParser extends Parser {
    private final String LOGTAG = "GpxPullParser";
    private final GpxPullParserListener listener;
    private final String trackPoint = "trkpt";

    public GpxPullParser(GpxPullParserListener newListener) throws NullPointerException {
        if (newListener == null) {
            throw new NullPointerException("Listener must not be null!");
        }
        listener = newListener;
    }

    @Override
    public void parse(String xml) {
        XmlPullParser xpp = null;
        try {
            xpp = buildXmlParser(xml);
        } catch (XmlPullParserException e) {
            Log.e(LOGTAG, "Parser building failed: " + e.getMessage());
            listener.onGpxError(e.getMessage());
            return;
        }
        boolean keepGoing = true;
        int trackPointCount = 0;
        while (keepGoing) {
            try {
                switch (xpp.next()) {
                    case XmlPullParser.START_TAG:
                        if (xpp.getName().equalsIgnoreCase(trackPoint)) {
                            trackPointCount++;
                            GpxTrackPoint point = parseTrackPoint(xpp);
                            if (point != null) {
                                listener.onGpxPoint(point);
                            }
                        }
                        break;
                    case XmlPullParser.START_DOCUMENT:
                        listener.onGpxStart();
                        break;
                    case XmlPullParser.END_DOCUMENT:
                        listener.onGpxEnd(trackPointCount);
                        keepGoing = false;
                        break;
                }
            } catch (Exception e) {
                Log.e(LOGTAG, "Error in loop: " + xpp.getName());
                listener.onGpxError(e.getMessage());
                keepGoing = false;
            }
        }
    }

    // Parse a singular track point.
    private GpxTrackPoint parseTrackPoint(XmlPullParser xpp) {
        GpxTrackPoint point = new GpxTrackPoint();
        try {
            point.setLon(Double.parseDouble(xpp.getAttributeValue(null, "lon")));
            point.setLat(Double.parseDouble(xpp.getAttributeValue(null, "lat")));
        } catch (NumberFormatException e) {
            Log.e(LOGTAG, "Parsing of double value in attribute failed: " + e.getMessage());
        } catch (IndexOutOfBoundsException e) {
            Log.e(LOGTAG, "Attribute not found: " + e.getMessage());
        }
        boolean keepGoing = true;
        String tag = "X", text;
        while (keepGoing) {
            try {
                switch (xpp.next()) {
                    case XmlPullParser.START_TAG:
                        tag = xpp.getName();
                        break;
                    case XmlPullParser.END_DOCUMENT:
                    case XmlPullParser.END_TAG: {
                        String endTag = xpp.getName();
                        if ((endTag == null) || endTag.equalsIgnoreCase(trackPoint)) {
                            // Done with this point.
                            keepGoing = false;
                        } else if (tag.equalsIgnoreCase(endTag)) {
                            // Revert back to the X so that TEXT won't be
                            // parsed.
                            tag = "X";
                        }
                        break;
                    }
                    case XmlPullParser.TEXT:
                        text = xpp.getText();
                        /**
                         * Expected values are:
                         * <ele>102.100000</ele>
                         * <time>2007-08-27T15:38:52.983Z</time>
                         * <course>189.449997</course>
                         * <speed>0.191927</speed>
                         * <fix>3d</fix>
                         * <sat>5</sat>
                         */
                        if (text == null) {
                            Log.e(LOGTAG, "BAD STUFF IN TRACK POINT ELEMENT!");
                            break;
                        }
                        if (!tag.equals("X")) {
                            if (tag.equalsIgnoreCase("ele")) {
                                point.setEle(Double.parseDouble(text));
                            } else if (tag.equalsIgnoreCase("time")) {
                                point.setTime(text);
                            } else if (tag.equalsIgnoreCase("course")) {
                                point.setCourse(Double.parseDouble(text));
                            } else if (tag.equalsIgnoreCase("speed")) {
                                point.setSpeed(Double.parseDouble(text));
                            } else if (tag.equalsIgnoreCase("fix")) {
                                point.setFix(text);
                            } else if (tag.equalsIgnoreCase("sat")) {
                                point.setSat(text);
                            }
                        }
                        break;
                }
            } catch (NumberFormatException e) {
                Log.e(LOGTAG, "Error processing trkpt: " + tag + ", " + e.getMessage());
                keepGoing = false;
                point = null;
            } catch (Exception e) {
                Log.e(LOGTAG, "Error processing trkpt: " + tag);
                listener.onGpxError(e.getMessage());
                keepGoing = false;
                point = null;
            }
        }
        return point;
    }
}
