package com.local.snoreguard;
import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Random;
public class SnoreDetectorTest {
    @Test public void learnsBackgroundWithoutTriggering() {
        SnoreDetector d=new SnoreDetector(3); Random r=new Random(42);
        for(int f=0;f<90;f++) { short[] n=new short[1024]; for(int i=0;i<n.length;i++) n[i]=(short)(r.nextGaussian()*350); assertFalse(d.process(n,n.length,16000).snore); }
    }
    @Test public void detectsLowFrequencySoundAboveBackground() {
        SnoreDetector d=new SnoreDetector(3);
        for(int f=0;f<30;f++) assertFalse(d.process(tone(100,120,f),1024,16000).snore);
        boolean detected=false; for(int f=30;f<45;f++) detected|=d.process(tone(120,7000,f),1024,16000).snore;
        assertTrue(detected);
    }
    private short[] tone(double hz,double amplitude,int frame) {
        short[] r=new short[1024]; for(int i=0;i<r.length;i++) { long n=(long)frame*r.length+i; r[i]=(short)(Math.sin(2*Math.PI*hz*n/16000)*amplitude); } return r;
    }
}
