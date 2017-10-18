package processing;

// FacePanel.java
// Andrew Davison, July 2013, ad@fivedots.psu.ac.th

/* This panel repeatedly snaps a picture and draw it onto
   the panel.  A face is highlighted with a yellow rectangle, which is updated 
   as the face moves. A "crosshairs" graphic is also drawn, positioned at the
   center of the rectangle.

  The highlighted part of the image can be saved.

  Face detection is done using a Haar face classifier in JavaCV. 
  It is executed inside its own thread since the processing can be lengthy,
  and I don't want the image grabbing speed to be affected.

  I borrowed some coding ideas from the JavaCV Android example in 
  samples\FacePreview.java
 */
import java.awt.*;
import javax.swing.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_imgproc.*;
import static org.bytedeco.javacpp.opencv_objdetect.*;
import static org.bytedeco.javacpp.helper.opencv_objdetect.cvHaarDetectObjects;
import org.bytedeco.javacpp.opencv_objdetect.CvHaarClassifierCascade;
import org.bytedeco.javacpp.videoInputLib.videoInput;
import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter.ToIplImage;

public class FacePanel extends JPanel implements Runnable {

    private FrameGrabber grabber;

    /* Dimensões de cada imagem; o painel é do mesmo tamanho das imagens */
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private static final int DELAY = 100;  // Tempo (ms) delay para desenhar o painel

    private static final int CAMERA_ID = 0;

    private static final int IM_SCALE = 4;
    private static final int SMALL_MOVE = 5;
    
    // tempo (ms) entre cada detecção de face
    private static final int DETECT_DELAY = 500;
    
    private static final int MAX_TASKS = 4;
    // Nr. máximo de tarefas que podem ficar aguardando no executor

    // Modelo cascata utilizado para detecção das faces (Disponibilizado pelo OpenCV)
    private static final String FACE_CASCADE_FNM = "haarcascade_frontalface_alt.xml";
    // "haarcascade_frontalface_alt2.xml";

    // Atributos para salvar uma imagem
    private static final String FACE_DIR = "savedFaces";
    private static final String FACE_FNM = "face";
    private static String FACE_ID;
    private static final int FACE_WIDTH = 125;
    private static final int FACE_HEIGHT = 150;

    private IplImage snapIm = null;
    private volatile boolean isRunning;
    private volatile boolean isFinished;

    // Usado para disponibilizar tempo da captura das imagens
    private int imageCount = 0;
    private long totalTime = 0;
    private Font msgFont;

    // Variáveis do JavaCV
    private CvHaarClassifierCascade classifier;
    private CvMemStorage storage;
    private CanvasFrame debugCanvas;
    private IplImage grayIm;

    // Usado para as threads que executam a detecção das faces
    private ExecutorService executor;
    private AtomicInteger numTasks;
    
    // Usado para armazenar número de tarefas de detecção
    private long detectStartTime = 0;

    private String identificacao;           // Identificação da pessoa pelo LBPH 
    private IplImage[] facesTreinamento;    // Armazena as faces para treinamento do LBPH
    private LBPHFaceRecognizer lbphFaceRecognizer;
    private Rectangle faceRect;     // holds the coordinates of the highlighted face

    private volatile boolean saveFace = false;
    private int fileCount = 0;   // used for constructing a filename for saving a face

    public FacePanel() {
        setBackground(Color.white);
        msgFont = new Font("SansSerif", Font.BOLD, 18);

        executor = Executors.newSingleThreadExecutor();
        /* this executor manages a single thread with an unbounded queue.
         Only one task can be executed at a time, the others wait.
         */
        numTasks = new AtomicInteger(0);
        // used to limit the size of the executor queue

        initDetector();
        faceRect = new Rectangle();
        lbphFaceRecognizer = new LBPHFaceRecognizer();
        facesTreinamento = new IplImage[lbphFaceRecognizer.getNUM_IMAGES_PER_PERSON()];

        isRunning = true;
        isFinished = false;

        new Thread(this).start();   // start updating the panel's image
    } // end of FacePanel()

    private BufferedImage loadImage(String imFnm) // return an image
    {
        BufferedImage image = null;
        try {
            image = ImageIO.read(new File(imFnm));   // read in as an image
            System.out.println("Reading image " + imFnm);
        } catch (Exception e) {
            System.out.println("Could not read image from " + imFnm);
        }
        return image;
    }  // end of loadImage()

    private void initDetector() {
        // instantiate a classifier cascade for face detection
        classifier = new CvHaarClassifierCascade(cvLoad(FACE_CASCADE_FNM));
        if (classifier.isNull()) {
            System.out.println("\nCould not load the classifier file: " + FACE_CASCADE_FNM);
            System.exit(1);
        }

        storage = CvMemStorage.create();  // create storage used during object detection

        // debugCanvas = new CanvasFrame("Debugging Canvas");
        // useful for showing JavaCV IplImage objects, to check on image processing
    }  // end of initDetector()

    public Dimension getPreferredSize() // make the panel wide enough for an image
    {
        return new Dimension(WIDTH, HEIGHT);
    }

    public void run() /* display the current webcam image every DELAY ms
     The time statistics gathered here will NOT include the time taken to
     find a face, which are farmed out to a separate thread in trackFace().

     Tracking is only started at least every DETECT_DELAY (1000) ms, and only
     if the number of tasks is < MAX_TASKS (one will be executing, the others
     waiting)
     */ {
        grabber = initGrabber(CAMERA_ID);
        if (grabber == null) {
            return;
        }

        long duration;

        while (isRunning) {
            long startTime = System.currentTimeMillis();

            snapIm = picGrab(grabber, CAMERA_ID);

            if (((System.currentTimeMillis() - detectStartTime) > DETECT_DELAY)
                    && (numTasks.get() < MAX_TASKS)) {
                trackFace(snapIm);
            }
            imageCount++;
            repaint();

            duration = System.currentTimeMillis() - startTime;
            totalTime += duration;

            if (duration < DELAY) {
                try {
                    Thread.sleep(DELAY - duration);  // wait until DELAY time has passed
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

        }
        closeGrabber(CAMERA_ID);
        System.out.println("Execution End");
        isFinished = true;

    }  // end of run()

    private FrameGrabber initGrabber(int ID) {
        FrameGrabber grabber = null;
        System.out.println("Initializing grabber for " + videoInput.getDeviceName(ID) + " ...");
        try {
            grabber = FrameGrabber.createDefault(ID);
            grabber.setFormat("dshow");       // using DirectShow
            grabber.setImageWidth(WIDTH);     // default is too small: 320x240
            grabber.setImageHeight(HEIGHT);
            grabber.start();
        } catch (Exception e) {
            System.out.println("Could not start grabber");
            System.out.println(e);
            System.exit(1);
        }
        return grabber;
    }  // end of initGrabber()

    private IplImage picGrab(FrameGrabber grabber, int ID) {
        IplImage im = null;
        OpenCVFrameConverter.ToIplImage conversor = new OpenCVFrameConverter.ToIplImage();
        try {
            im = conversor.convert(grabber.grab());  // take a snap
        } catch (Exception e) {
            System.out.println("Problem grabbing image for camera " + ID);
        }
        return im;
    }  // end of picGrab()

    public void closeGrabber(int ID) {
        try {
            grabber.stop();
            grabber.release();
        } catch (Exception e) {
            System.out.println("Problem stopping grabbing for camera " + ID);
        }
    }  // end of closeGrabber()

    public void paintComponent(Graphics g) /* Draw the image, the rectangle (and crosshairs) around a detected
     face, and the average ms snap time at the bottom left of the panel. 
     This time does NOT include the face detection task.
     */ {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setFont(msgFont);

        // draw the image, stats, and detection rectangle
        if (snapIm != null) {
            g2.setColor(Color.YELLOW);
            OpenCVFrameConverter.ToIplImage grabberConverter = new OpenCVFrameConverter.ToIplImage();
            Java2DFrameConverter paintConverter = new Java2DFrameConverter();
            org.bytedeco.javacv.Frame frame = grabberConverter.convert(snapIm);
            g2.drawImage(paintConverter.getBufferedImage(frame, 1), 0, 0, this);   // draw the snap
            String statsMsg = String.format("Snap Avg. Time:  %.1f ms",
                    ((double) totalTime / imageCount));

            String box_text = "Identificado: " + identificacao;
            g2.drawString(box_text, 5, 50);

            g2.drawString(statsMsg, 5, HEIGHT - 10);
            // write statistics in bottom-left corner

            drawRect(g2);
        } else {  // no image yet
            g2.setColor(Color.BLUE);
            g2.drawString("Loading from camera " + CAMERA_ID + "...", 5, HEIGHT - 10);
        }
    } // end of paintComponent()

    private void drawRect(Graphics2D g2) /* use the face rectangle to draw a yellow rectangle around the face, with 
     crosshairs at its center.
     The drawing of faceRect is in a synchronized block since it may be being
     updated or used for image saving at the same time in other threads.
     */ {
        synchronized (faceRect) {
            if (faceRect.width == 0) {
                return;
            }

            // draw a thick yellow rectangle
            g2.setColor(Color.GREEN);
            g2.setStroke(new BasicStroke(2));
            g2.drawRect(faceRect.x, faceRect.y, faceRect.width, faceRect.height);

        }
    }  // end of drawRect()

    public void closeDown() /* Terminate run() and wait for it to finish.
     This stops the application from exiting until everything
     has finished. */ {
        isRunning = false;
        while (!isFinished) {
            try {
                Thread.sleep(DELAY);
            } catch (Exception ex) {
            }
        }
    } // end of closeDown()

    // ------------------------- face tracking ----------------------------
    private void trackFace(IplImage img) /* Create a separate thread for the time-consuming detection task:
       find a face in the current image, store its coordinates in faceRect, and
       save the face part of the image in a file if requested. 
     Print the time taken for all of this to stdout.
     */ {
        numTasks.getAndIncrement();     // increment no. of tasks before entering queue
        executor.execute(new Runnable() {
            public void run() {
                detectStartTime = System.currentTimeMillis();
                CvRect rect = findFace(img);
                if (rect != null) {

                    // Seta parâmetros do retângulo
                    setRectangle(rect);
                }
                long detectDuration = System.currentTimeMillis() - detectStartTime;
                System.out.println(" detection duration: " + detectDuration + "ms");
                numTasks.getAndDecrement();  // decrement no. of tasks since finished
            }
        });
    }  // end of trackFace()

    private IplImage scaleGray(IplImage img) /* Scale the image and convert it to grayscale. Scaling makes
     the image smaller and so faster to process, and Haar detection
     requires a grayscale image as input
     */ {
        // convert to grayscale
        IplImage grayImg = cvCreateImage(cvGetSize(img), IPL_DEPTH_8U, 1);
        cvCvtColor(img, grayImg, CV_BGR2GRAY);

        // scale the grayscale (to speed up face detection)
        IplImage smallImg = IplImage.create(grayImg.width() / IM_SCALE,
                grayImg.height() / IM_SCALE, IPL_DEPTH_8U, 1);
        cvResize(grayImg, smallImg, CV_INTER_LINEAR);

        // equalize the small grayscale
        cvEqualizeHist(smallImg, smallImg);
        return smallImg;
    }  // end of scaleGray()

    private CvRect findFace(IplImage img) // Find a single face using the Haar detector
    {
        // Converte para escalas de Cinza
        grayIm = scaleGray(img);

        /*
     // show the greyscale image to check on image processing steps
     debugCanvas.showImage(grayIm);
	 debugCanvas.waitKey(0);
         */
        // System.out.println("Detecting largest face...");   // cvImage
        CvSeq faces = cvHaarDetectObjects(grayIm, classifier, storage, 1.1, 1, // 3
                // CV_HAAR_SCALE_IMAGE |
                CV_HAAR_DO_ROUGH_SEARCH | CV_HAAR_FIND_BIGGEST_OBJECT);
        // speed things up by searching for only a single, largest face subimage

        int total = faces.total();
        if (total == 0) {
            System.out.println("No faces found");
            return null;
        } else if (total > 1) // this case should not happen, but included for safety
        {
            System.out.println("Multiple faces detected (" + total + "); using the first");
        } else {
            System.out.println("Face detected");
        }

        CvRect rect = new CvRect(cvGetSeqElem(faces, 0));
        
        if (saveFace) {
            learnNewFace(img);
        }

        //Identifica o rosto
        identificacao = lbphFaceRecognizer.identifyFace(grayIm);

        cvClearMemStorage(storage);
        return rect;
    }  // end of findface()

    private void setRectangle(CvRect r) /* Extract the (x, y, width, height) values of the highlighted image from
     the JavaCV rectangle data structure, and store them in a Java rectangle.
     In the process, undo the scaling which was applied to the image before face 
     detection was carried out.
     Report any movement of the new rectangle compared to the previous one.
     The updating of faceRect is in a synchronized block since it may be used 
     for drawing or image saving at the same time in other threads.
     */ {
        synchronized (faceRect) {
            int xNew = r.x() * IM_SCALE;
            int yNew = r.y() * IM_SCALE;
            int widthNew = r.width() * IM_SCALE;
            int heightNew = r.height() * IM_SCALE;

            // calculate movement of the new rectangle compared to the previous one
            int xMove = (xNew + widthNew / 2) - (faceRect.x + faceRect.width / 2);
            int yMove = (yNew + heightNew / 2) - (faceRect.y + faceRect.height / 2);

            // report movement only if it is 'significant'
            if ((Math.abs(xMove) > SMALL_MOVE) || (Math.abs(yMove) > SMALL_MOVE)) {
                System.out.println("Movement (x,y): (" + xMove + "," + yMove + ")");
            }

            faceRect.setRect(xNew, yNew, widthNew, heightNew);
        }
    }  // end of setRectangle()

    // ---------------- face saving -------------------------
    public void saveFace(String faceID) {
        saveFace = true;
        FACE_ID = faceID;
    }

    private void learnNewFace(IplImage img) {
        numTasks.getAndIncrement();         // increment no. of tasks before entering queue
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < lbphFaceRecognizer.getNUM_IMAGES_PER_PERSON(); i++) {
                        facesTreinamento[i] = img;//clipSaveFace(img);
                    }
                    lbphFaceRecognizer.saveNewFace(FACE_ID, facesTreinamento);
                    saveFace = false;
                } catch (Exception ex) {
                    Logger.getLogger(FacePanel.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    private IplImage clipSaveFace(IplImage img) /* clip the image using the current face rectangle, and save it into fnm
     The use of faceRect is in a synchronized block since it may be being
     updated or used for drawing at the same time in other threads.
     */ {
        BufferedImage clipIm = null;

        synchronized (faceRect) {
            if (faceRect.width == 0) {
                System.out.println("No face selected");
                return img;
            }
            OpenCVFrameConverter.ToIplImage grabberConverter = new OpenCVFrameConverter.ToIplImage();
            Java2DFrameConverter paintConverter = new Java2DFrameConverter();
            org.bytedeco.javacv.Frame frame = grabberConverter.convert(img);
            BufferedImage im = paintConverter.getBufferedImage(frame, 1);
            try {
                clipIm = im.getSubimage(faceRect.x, faceRect.y, faceRect.width, faceRect.height);
            } catch (RasterFormatException e) {
                System.out.println("Could not clip the image");
            }
        }
        if (clipIm != null) {
            BufferedImage grayIm = resizeImage(clipIm);
            BufferedImage faceIm = clipToFace(grayIm);
            return toIplImage(faceIm);
        } else {
            return img;
        }
    }  // end of clipSaveFace()

    private void saveClip(BufferedImage clipIm) /* resizes to at least a standard size, converts to grayscale, 
     clips to an exact size, then saves in a standard location */ {
        long startTime = System.currentTimeMillis();

        System.out.println("Saving clip...");
        BufferedImage grayIm = resizeImage(clipIm);
        BufferedImage faceIm = clipToFace(grayIm);
        saveImage(faceIm, FACE_DIR + "/" + FACE_FNM + fileCount + ".png");
        fileCount++;

        System.out.println("  Save time: " + (System.currentTimeMillis() - startTime) + " ms");
    }  // end of saveClip()

    private BufferedImage resizeImage(BufferedImage im) // resize to at least a standard size, then convert to grayscale 
    {
        // resize the image so *at least* FACE_WIDTH*FACE_HEIGHT size
        int imWidth = im.getWidth();
        int imHeight = im.getHeight();
        System.out.println("Original (w,h): (" + imWidth + ", " + imHeight + ")");

        double widthScale = FACE_WIDTH / ((double) imWidth);
        double heightScale = FACE_HEIGHT / ((double) imHeight);
        double scale = (widthScale > heightScale) ? widthScale : heightScale;

        int nWidth = (int) Math.round(imWidth * scale);
        int nHeight = (int) Math.round(imHeight * scale);

        // convert to grayscale while resizing
        BufferedImage grayIm = new BufferedImage(nWidth, nHeight,
                BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2 = grayIm.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(im, 0, 0, nWidth, nHeight, 0, 0, imWidth, imHeight, null);
        g2.dispose();

        System.out.println("Scaled gray (w,h): (" + nWidth + ", " + nHeight + ")");
        return grayIm;
    }  // end of resizeImage()

    private BufferedImage clipToFace(BufferedImage im) // clip image to FACE_WIDTH*FACE_HEIGHT size
    // I assume the input image is face size or bigger
    {
        int xOffset = (im.getWidth() - FACE_WIDTH) / 2;
        int yOffset = (im.getHeight() - FACE_HEIGHT) / 2;
        BufferedImage faceIm = null;
        try {
            faceIm = im.getSubimage(xOffset, yOffset, FACE_WIDTH, FACE_HEIGHT);
            System.out.println("Clipped image to face dimensions: ("
                    + FACE_WIDTH + ", " + FACE_HEIGHT + ")");
        } catch (RasterFormatException e) {
            System.out.println("Could not clip the image");
            OpenCVFrameConverter.ToIplImage grabberConverter = new OpenCVFrameConverter.ToIplImage();
            Java2DFrameConverter paintConverter = new Java2DFrameConverter();
            org.bytedeco.javacv.Frame frame = grabberConverter.convert(grayIm);
            faceIm = paintConverter.getBufferedImage(frame);
        }
        return faceIm;
    }  // end of clipToFace()

    private void saveImage(BufferedImage im, String fnm) // save image in fnm
    {
        try {
            ImageIO.write(im, "png", new File(fnm));
            System.out.println("Saved image to " + fnm);
        } catch (IOException e) {
            System.out.println("Could not save image to " + fnm);
        }
    }  // end of saveImage()

    private IplImage toIplImage(BufferedImage bufImage) {

        ToIplImage iplConverter = new OpenCVFrameConverter.ToIplImage();
        Java2DFrameConverter java2dConverter = new Java2DFrameConverter();
        IplImage iplImage = iplConverter.convert(java2dConverter.convert(bufImage));
        return iplImage;
    }

    public void pausarThread() {
        isRunning = false;
    }

    public void reiniciarThread() {
        isRunning = true;
    }

    public IplImage getGrayIm() {
        return grayIm;
    }

    public void setGrayIm(IplImage grayIm) {
        this.grayIm = grayIm;
    }

    public processing.LBPHFaceRecognizer getLbphFaceRecognizer() {
        return lbphFaceRecognizer;
    }

    public void setLbphFaceRecognizer(processing.LBPHFaceRecognizer lbphFaceRecognizer) {
        this.lbphFaceRecognizer = lbphFaceRecognizer;
    }

} // end of FacePanel class

