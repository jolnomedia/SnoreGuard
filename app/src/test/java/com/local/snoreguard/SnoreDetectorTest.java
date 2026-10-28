package com.local.snoreguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SnoreDetectorTest {
    private static final int RATE = 16000;
    private static final int SIZE = 1024;

    private short[] tone(double frequency, double amplitude, int seed) {
        short[] result = new short[SIZE];
        for (int i = 0; i < SIZE; i++) {
            double noise = ((i * 31 + seed * 17) % 101 - 50) * 0.3;
            result[i] = (short) (amplitude * Math.sin(2 * Math.PI * frequency * i / RATE) + noise);
        }
        return result;
    }

    @Test public void quietBackgroundDoesNotTrigger() {
        SnoreDetector detector = new SnoreDetector(3);
        boolean triggered = false;
        for (int i = 0; i < 100; i++) triggered |= detector.process(tone(130, 90, i), SIZE, RATE).snore;
        assertFalse(triggered);
    }

    @Test public void calibrates32FramesAndAveragesPerFrameDb() {
        SnoreDetector detector = new SnoreDetector(3);
        double sumDb = 0;
        SnoreDetector.Analysis analysis = null;
        for (int i = 0; i < SnoreDetector.CALIBRATION_FRAMES; i++) {
            short[] frame = tone(130, 1000, i);
            analysis = detector.process(frame, SIZE, RATE);
            double sumSquares = 0;
            for (short sample : frame) sumSquares += (double) sample * sample;
            double rms = Math.sqrt(sumSquares / SIZE);
            sumDb += Math.max(-90, 20 * Math.log10(Math.max(rms, 0.00001)));
            assertEquals(i == 31, analysis.calibrated);
            assertFalse(analysis.snore);
        }
        assertEquals(sumDb / 32, analysis.baselineDb, 0.00001);
    }

    @Test public void loudLowFrequencyAboveBackgroundTriggers() {
        SnoreDetector detector = new SnoreDetector(3);
        for (int i = 0; i < 32; i++) detector.process(tone(130, 100, i), SIZE, RATE);
        boolean triggered = false;
        for (int i = 0; i < 30; i++) triggered |= detector.process(tone(130, 9000, i + 40), SIZE, RATE).snore;
        assertTrue(triggered);
    }
}
