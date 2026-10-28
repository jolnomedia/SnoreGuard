package com.local.snoreguard;
/** On-device acoustic heuristic; audio samples are discarded after each frame. */
public final class SnoreDetector {
    public static final class Analysis {
        public final double db, baselineDb, lowBandRatio;
        public final boolean calibrated, snore;
        Analysis(double db, double baselineDb, double lowBandRatio, boolean calibrated, boolean snore) {
            this.db=db; this.baselineDb=baselineDb; this.lowBandRatio=lowBandRatio; this.calibrated=calibrated; this.snore=snore;
        }
    }
    private static final int CALIBRATION_FRAMES=28;
    private static final double[] FREQUENCIES={70,100,140,200,280,400,600,900,1300,2000};
    private final int sensitivity;
    private final double[] previous=new double[FREQUENCIES.length];
    private int calibrationCount, candidateFrames;
    private double calibrationSum, baselineDb=-55;
    public SnoreDetector(int sensitivity) { this.sensitivity=Math.max(1,Math.min(5,sensitivity)); }
    public Analysis process(short[] samples,int count,int sampleRate) {
        double sumSquares=0; int crossings=0;
        for(int i=0;i<count;i++) {
            double value=samples[i]/32768.0; sumSquares+=value*value;
            if(i>0&&((samples[i-1]<0&&samples[i]>=0)||(samples[i-1]>=0&&samples[i]<0))) crossings++;
        }
        double rms=Math.sqrt(sumSquares/Math.max(1,count));
        double db=Math.max(-90,20*Math.log10(Math.max(rms,0.00001)));
        double lowRatio=lowBandRatio(samples,count,sampleRate);
        if(calibrationCount<CALIBRATION_FRAMES) {
            calibrationSum+=db; calibrationCount++;
            if(calibrationCount==CALIBRATION_FRAMES) baselineDb=calibrationSum/CALIBRATION_FRAMES;
            return new Analysis(db,baselineDb,lowRatio,false,false);
        }
        if(candidateFrames==0) baselineDb=baselineDb*0.997+db*0.003;
        double extraDb=15-sensitivity*2;
        double lowBandThreshold=0.66-sensitivity*0.025;
        double zcr=(double)crossings/Math.max(1,count);
        boolean candidate=db>baselineDb+extraDb&&lowRatio>lowBandThreshold&&zcr>0.004&&zcr<0.22;
        boolean snore=false;
        if(candidate) { candidateFrames++; if(candidateFrames>=3) { snore=true; candidateFrames=0; } }
        else candidateFrames=Math.max(0,candidateFrames-1);
        return new Analysis(db,baselineDb,lowRatio,true,snore);
    }
    private double lowBandRatio(short[] samples,int count,int sampleRate) {
        double total=0,low=0;
        for(int f=0;f<FREQUENCIES.length;f++) {
            double omega=2*Math.PI*FREQUENCIES[f]/sampleRate,coeff=2*Math.cos(omega),s0,s1=0,s2=0;
            for(int i=0;i<count;i++) { s0=samples[i]+coeff*s1-s2; s2=s1; s1=s0; }
            double energy=Math.max(0,s1*s1+s2*s2-coeff*s1*s2);
            energy=energy*0.8+previous[f]*0.2; previous[f]=energy; total+=energy; if(f<=4) low+=energy;
        }
        return total>0?low/total:0;
    }
}
