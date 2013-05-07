package com.twolinessoftware.android.framework.service.comms.gps;

import java.io.FileInputStream;

import net.sf.marineapi.nmea.event.SentenceEvent;
import net.sf.marineapi.nmea.event.SentenceListener;
import net.sf.marineapi.nmea.io.SentenceReader;
import net.sf.marineapi.nmea.sentence.RMCSentence;
import net.sf.marineapi.nmea.sentence.SentenceId;
import android.util.Log;

import com.twolinessoftware.android.framework.service.comms.Parser;

public class NmeaParser extends Parser implements SentenceListener {

    private final String LOGTAG = NmeaParser.class.getSimpleName();
    private final GpxPullParserListener listener;
    private SentenceReader reader;

    public NmeaParser(GpxPullParserListener newListener) throws NullPointerException {
        if (newListener == null) {
            throw new NullPointerException("Listener must not be null!");
        }
        listener = newListener;
    }

    @Override
    public void parse(String xml) {
        throw new UnsupportedOperationException("String parsing is not supported in NmeaParser!");
    }

    @Override
    public void parse(FileInputStream fIS) {
        reader = new SentenceReader(fIS);
        reader.addSentenceListener(this, SentenceId.RMC);
        reader.start();
    }

    @Override
    public void readingPaused() {
        Log.d(LOGTAG, "Reading paused.");
    }

    @Override
    public void readingStarted() {
        listener.onGpxStart();
    }

    @Override
    public void readingStopped() {
        listener.onGpxEnd();
    }

    @Override
    public void sentenceRead(SentenceEvent event) {
        RMCSentence sentence = (RMCSentence) event.getSentence();
        GpxTrackPoint point = new GpxTrackPoint();
        point.setLat(sentence.getPosition().getLatitude());
        point.setLon(sentence.getPosition().getLongitude());
        point.setTime(sentence.getTime().getMilliseconds());
        point.setCourse(sentence.getCourse());
        point.setSat("8");
        point.setEle(sentence.getPosition().getAltitude());
        point.setSpeed(sentence.getSpeed());
        listener.onGpxPoint(point);
    }

    /**
     * Stops the parsing.
     */
    public void stop() {
        reader.stop();
    }
}
