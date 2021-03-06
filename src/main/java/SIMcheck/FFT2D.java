/*
 *  Copyright (c) 2015, Graeme Ball,
 *  University of Oxford, Department of Biochemistry.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see http://www.gnu.org/licenses/ .
 */

package SIMcheck;
import ij.*;
import ij.measure.Calibration;
import ij.process.*;
import ij.gui.Roi;
import ij.gui.OvalRoi;
import ij.plugin.filter.GaussianBlur;

/** Improved FFT extends the ImageJ 2D FHT class, adding extra methods for:
 * calculation of phase image, suppression of low frequencies, and different
 * result scaling options.
 * @author Graeme Ball <graemeball@gmail.com>
 */
public class FFT2D extends FHT {
    
    private static final double WIN_FRACTION_DEFAULT = 0.06d;
    private static final double NO_GAMMA = 0.0d;  // no gamma correction
    // tolerance to check if a double precision float is approx. equal to 0
    private static final double ZERO_TOL = 0.000001d;

    public FFT2D(ImageProcessor ip){
        super(ip);
    }
    
    /** Return real component image of Fourier transform. */
    public ImageProcessor getComplexReal() {
        ImageStack complexFourier = super.getComplexTransform();
        return complexFourier.getProcessor(1);
    }

    /** Return imaginary component image of Fourier transform. */
    public ImageProcessor getComplexImag() {
        ImageStack complexFourier = super.getComplexTransform();
        return complexFourier.getProcessor(2);
    }

    /** 
     * Return absolute value image of Fourier transform (32-bit,
     * no log scaling) for this object's FHT.
     */
    public ImageProcessor getComplexAbs() {
        FHT fht = (FHT)this;
        return getComplexAbs(fht, false);
    }

    /** 
     * Static method returning absolute value image of Fourier transform 'fht'
     * passed as a parameter (result is 32-bit, no log scaling, optional ^2). 
     */
    public static ImageProcessor getComplexAbs(FHT fht, boolean squared) {
        ImageStack complexFourier = fht.getComplexTransform();
        FloatProcessor fpReal =
            (FloatProcessor)complexFourier.getProcessor(1).convertToFloat();
        float[] realPix = (float[])fpReal.getPixels();
        FloatProcessor fpImag =
            (FloatProcessor)complexFourier.getProcessor(2).convertToFloat();
        float[] imagPix = (float[])fpImag.getPixels();
        float[] absPix = new float[realPix.length];
        if (squared) {
            for(int i=0; i<realPix.length; i++) {
                absPix[i] = realPix[i] * realPix[i] + imagPix[i] * imagPix[i];
            }
        } else {
            // duplication of code above to prevent 'i' if tests...
            for(int i=0; i<realPix.length; i++) {
                absPix[i] = (float)Math.sqrt((double)(realPix[i] * realPix[i] 
                                             + imagPix[i] * imagPix[i]));
            }
        }
        int w = fpReal.getWidth();
        int h = fpReal.getHeight();
        FloatProcessor absFp = new FloatProcessor(w, h, absPix);
        return (ImageProcessor)absFp;
    }

    /** Return phase image of Fourier transform (radians). */
    public ImageProcessor getComplexPhase() {
        ImageStack complexFourier = super.getComplexTransform();
        FloatProcessor fpReal =
            (FloatProcessor)complexFourier.getProcessor(1).convertToFloat();
        float[] realPix = (float[])fpReal.getPixels();
        FloatProcessor fpImag =
            (FloatProcessor)complexFourier.getProcessor(2).convertToFloat();
        float[] imagPix = (float[])fpImag.getPixels();
        float[] phasePix = new float[realPix.length];
        for (int i=0; i<realPix.length; i++) {
            phasePix[i] = calcPhase(realPix[i], imagPix[i]);
        }
        int w = fpReal.getWidth();
        int h = fpReal.getHeight();
        FloatProcessor phaseFp = new FloatProcessor(w, h, phasePix);
        return (ImageProcessor)phaseFp;
    }

    /** 
     * Pad a slice to a square image of size equal to the nearest
     * power of 2. Copied from ImageJ's FFT.java. 
     */
    public static ImageProcessor pad(ImageProcessor ip, int padSize) {
        ImageProcessor ip2 = ip.createProcessor(padSize, padSize);
        ip2.setValue(0);
        ip2.fill();
        ip2.insert(ip, 0, 0);
        Undo.reset();
        return ip2;
    }

    /** 
     * Apply Guassian window function to suppress high-freq tiling artifacts.
     * @param ip for slice to process 
     * @param pc percentile (0 - 1) of image width / height for window
     */
    public static ImageProcessor gaussWindow(ImageProcessor ip, double pc) {
        int nx = ip.getWidth();
        int ny = ip.getHeight();
        int winx = (int)(pc * (double)nx);
        int winy = (int)(pc * (double)ny);
        double gaussWidthX =  0.25d * winx;
        double gaussWidthY =  0.25d * winy;
        FloatProcessor fp = (FloatProcessor)ip.duplicate().convertToFloat();
        FloatProcessor winIp = (FloatProcessor)fp.duplicate();
        float[] winPix = (float[])winIp.getPixels();
        int npix = winPix.length;
        for (int i = 0; i < npix; i++) {
            int x = i % nx;
            int y = i / nx;
            if (x < winx || x >= nx - winx || y < winy || y >= ny - winy) {
                winPix[i] = 0.0f;
            } else {
                winPix[i] = 1.0f;
            }
        }
        ImagePlus winImp = new ImagePlus("gaussWin", winIp);
        GaussianBlur gblur = new GaussianBlur();
        gblur.showProgress(false);
        gblur.blurFloat(winIp, gaussWidthX, gaussWidthY, 0.002);
        FloatProcessor winFp = (FloatProcessor)winImp.getProcessor();
        float[] wini = (float[])winFp.getPixels();
        float[] fpix = (float[])fp.getPixels();
        for (int i = 0; i < npix; i++) {
            float pixi = fpix[i] * wini[i];
            fpix[i] = pixi;
        }
        return (ImageProcessor)fp;
    }

    /** FFT a slice, returning new fht object. **/
    public static FHT fftSlice(ImageProcessor ip, ImagePlus imp) {
        FHT fht = new FHT(ip);
        fht.originalWidth = imp.getWidth();
        fht.originalHeight = imp.getHeight();
        fht.originalBitDepth = imp.getBitDepth();
        fht.setShowProgress(false);
        fht.transform();
        return fht;
    }

    /**
     * 2D Fourier Transform each slice in a hyperstack using ImageJ's FHT class,
     * with options to control window function, result type and scaling.
     * @param impIn ImagePlus to be Fourier-transformed
     * @param floatResult result type: true=float, false=8-bit 
     * @param winFraction window function size as a fraction 0-1 of input size
     * @param gamma result gamma scaling (gamma=0.0d gives log-scaled result)
     * @return ImagePlus where each 2D slice has been Fourier transformed
     */
    public static ImagePlus fftImp(ImagePlus impIn, boolean floatResult,
            double winFraction, double gamma)
    {
        ImagePlus imp = impIn.duplicate();
        Calibration cal = impIn.getCalibration();
        int width = imp.getWidth();
        int height = imp.getHeight();
        int channels = imp.getNChannels();
        int Zplanes = imp.getNSlices();
        int frames = imp.getNFrames();
        int currentSlice = imp.getSlice();
        ImageStack stack = imp.getStack();
        int slices = stack.getSize();
        int paddedSize = calcPadSize(imp);  // padding requirement
        if (paddedSize != width || paddedSize != height) {
            width = paddedSize;
            height = paddedSize;
            cal.pixelWidth *= (double)width / paddedSize;
            cal.pixelHeight *= (double)height / paddedSize;
        }
        imp.setCalibration(cal);
        ImageStack stackF = new ImageStack(width, height);
        double progress = 0;
        FHT fht = null;
        for (int slice = 1; slice <= slices; slice++) {
            // calculate FFT / power spectrum
            // NB: ImagePlus has code to deal with power spectrum display.
            //     FFT.java stores FHT transform result *and* original image.
            // See: ij/plugin/FFT.java & ij/ImagePlus.java (search FHT & FFT)
            //      ij/process/FHT.java
            ImageProcessor ip = stack.getProcessor(slice);
            if (Math.abs(winFraction) > ZERO_TOL) {
                ip = FFT2D.gaussWindow(ip, winFraction);
            }
            ip = FFT2D.pad(ip, paddedSize);  
            fht = FFT2D.fftSlice(ip, imp);
            ImageProcessor ps = null;
            if (gamma > 0.0d) {
                ps = gammaScaledAmplitude(fht, gamma);
                // FIXME: does not return 8-bit even if floatResult==false
            } else {
                if (floatResult) {
                    ps = logScaledPowerSpectrum(fht);
                } else {
                    ps = fht.getPowerSpectrum();
                }
            }
            stackF.addSlice(String.valueOf(slice), ps);  // FFT power spectrum
            progress += (double) slice / (double) slices;
            IJ.showProgress(progress); 
        }
        String title = "FFT2D_" + impIn.getTitle();
        ImagePlus impF = new ImagePlus(title, stackF);
        impF.copyScale(imp);
        impF.setProperty("FHT", fht);
        impF.setDimensions(channels, Zplanes, frames);
        impF.setSlice(currentSlice);
        impF.setOpenAsHyperStack(true);
        return impF;
    }
    
    /** fftImp with default IJ options: log-scaled Amp^2, 8-bit result. */
    public static ImagePlus fftImp(ImagePlus impIn) {
        return fftImp(impIn, false, WIN_FRACTION_DEFAULT, NO_GAMMA);
    }
    
    /**
     * fftImp with default IJ options: log-scaled Amp^2, 8-bit result,
     * but specified window function winFraction.
     */
    public static ImagePlus fftImp(ImagePlus impIn, double winFraction) {
        return fftImp(impIn, false, winFraction, NO_GAMMA);
    }

    /** Return power spectrum: amplitude squared, scaled by 10log10. */ 
    public static ImageProcessor logScaledPowerSpectrum(FHT fht)
    {
        FloatProcessor fp = (FloatProcessor)getComplexAbs(fht, true);
        float[] absPix = (float[])fp.getPixels();
        int nPix = absPix.length;
        float[] psPix = new float[nPix];
        for (int i = 0; i < nPix; i++) {
            double pwr = Math.log(absPix[i]);
            psPix[i] = (float)pwr;
        }
        fp.setPixels(psPix);  // N.B. update min and max after setPixels!
        fp.setMinAndMax(J.min(psPix), J.max(psPix));
        return fp;
    }

    /** Return gamma-scaled FFT absolute values. */
    public static ImageProcessor gammaScaledAmplitude(
            FHT fht, double gamma)
    {
        ImageProcessor ipAbs = getComplexAbs(fht, false);
        float[] psAbsPix = (float[])ipAbs.getPixels();
        int nPix = psAbsPix.length;
        for (int i = 0; i < nPix; i++) {
            psAbsPix[i] = (float)Math.pow((double)psAbsPix[i], gamma);
        }
        ipAbs.setPixels(psAbsPix);  // N.B. update min and max after setPixels!
        ipAbs.setMinAndMax(J.min(psAbsPix), J.max(psAbsPix));
        return ipAbs;
    }

    /** 
     * Calculate the padded width and height for an ImagePlus to be
     * Fourier-transformed. Copied from ImageJ's FFT.java 
     */
    public static int calcPadSize(ImagePlus imp) {
        int originalWidth = imp.getWidth();
        int originalHeight = imp.getHeight();
        int size = Math.max(originalWidth, originalHeight);
        int padSize = 2;
        while (padSize < size) 
            padSize *= 2;
        return padSize;
    }

    /** 
     * Calculate the padded width and height for an ImagePlus to be
     * Fourier-transformed. Copied from ImageJ's FFT.java 
     */
    public static int calcPadSize(ImageProcessor ip) {
        int originalWidth = ip.getWidth();
        int originalHeight = ip.getHeight();
        int size = Math.max(originalWidth, originalHeight);
        int padSize = 2;
        while (padSize < size) 
            padSize *= 2;
        return padSize;
    }

    /**
     * Filter low/offset frequencies from a Fourier transform result.
     * @param fp input FloatProcessor containig FFT amplitudes
     * @param centralRadius radius (pixels) of central Gaussian attenuation
     * @param lineHalfWidth in pixels, to suppress kx~0, ky~0
     * @return filtered result
     */
    public static ImageProcessor filterLow(FloatProcessor fp,
                                    double centralRadius,
                                    double lineHalfWidth) {
        int width = fp.getWidth();
        int height = fp.getHeight();
        int lineHW = (int)(lineHalfWidth*(double)width);
        FloatProcessor mask = new FloatProcessor(width, height);
        mask.setColor(1);
        mask.fill();  // set image to 1 then mask regions using 0-1 below
        mask.setColor(0);
        if (lineHalfWidth > 0) {
            // blank out the vertical stripe
            Roi verticalStripe = new Roi((double)(width/2 - lineHW), (double)0, 
                                            (double)2*lineHW, (double)(height-1));
            mask.draw(verticalStripe);
            mask.fill(verticalStripe);
            // blank out the horizontal stripe
            Roi horizontalStripe = new Roi((double)0, (double)(height/2 - lineHW),
                                            (double)(width-1), (double)2*lineHW);
            mask.draw(horizontalStripe);
            mask.fill(horizontalStripe);
        }
        // suppress low freq / zero order
        int blobRad = (int)(centralRadius*(double)width);
        OvalRoi centralBlob = new OvalRoi((double)(width/2 - blobRad),
                                            (double)(height/2 - blobRad),
                                            (double)(blobRad*2),
                                            (double)(blobRad*2));
        mask.draw((Roi)centralBlob);
        mask.fill((Roi)centralBlob);
        GaussianBlur smudge = new GaussianBlur();
        smudge.blurFloat((FloatProcessor)mask, 3.0, 3.0, 0.01);
        // multiply input fp with smoothed mask
        float[] fpPix = (float[])fp.getPixels();
        float[] maskPix = (float[])mask.getPixels();
        for (int i=0; i<fpPix.length; i++){
            fp.setf(i, fpPix[i] * maskPix[i]);
        }
        return (ImageProcessor)fp;
    }

    /** Calculate phase (radians) using real + imaginary components. **/
    private static float calcPhase(float re, float im) {
        if (re > 0) {
            return (float)Math.atan((double)im/re);
        }else if ((re < 0) && (im >= 0)) {
            return (float)(Math.atan((double)im/re) + Math.PI);
        }else if ((re < 0) && (im < 0)) {
            return (float)(Math.atan((double)im/re) - Math.PI);
        }else if ((re == 0) && (im > 0)) {
            return (float)Math.PI/2;
        }else if ((re == 0) && (im < 0)) {
            return (float)-Math.PI/2;
        }else{
            return (float)0;
        }
    }
    
    /** Manual test method. */
    public static void main(String[] args) {
        System.out.println("Testing FFT2D.java");
        new ImageJ();
        // create x-gradient test image
        int nx = 300;
        int ny = 200;
        FloatProcessor fp = new FloatProcessor(nx, ny);
        float[] pixels = new float[nx * ny];
        int xpos = 0;
        float imMax = 32000.0f;
        for (int p = 0; p < pixels.length; p++) {
            pixels[p] = imMax * (float) xpos / nx;
            xpos++;
            if (xpos == nx) {
                xpos = 0;
            }
        }
        fp.setPixels(pixels);
        ImagePlus impRaw = new ImagePlus("gradient_raw", fp);
        impRaw.show();
        FloatProcessor fp2 = new FloatProcessor(nx, ny);
        fp2 = (FloatProcessor) FFT2D.gaussWindow(fp.duplicate(), 0.125d);
        ImagePlus impWinFunc = new ImagePlus("gradient_win", fp2);
        impWinFunc.show();
        FloatProcessor fp3 = new FloatProcessor(nx, ny);
        fp3 = (FloatProcessor)FFT2D.pad(fp2.duplicate(), FFT2D.calcPadSize(impWinFunc));
        ImagePlus impPadWin = new ImagePlus("gradient_win_pad", fp3);
        impPadWin.show();
        ImagePlus wfTest = TestData.recon;
        FloatProcessor fpWFtest = (FloatProcessor)wfTest.getProcessor();
        fpWFtest = (FloatProcessor)FFT2D.gaussWindow(fpWFtest.duplicate(), 0.04d);
        ImagePlus impWFtestResult = new ImagePlus("WindowFunctionTest", fpWFtest);
        impWFtestResult.copyScale(wfTest);
        impWFtestResult.show();
        FFT2D.fftImp(impWFtestResult).show();
    }
}
