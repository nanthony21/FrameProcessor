package org.micromanager.plugins.frameprocessor;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.micromanager.LogManager;
import org.micromanager.PropertyMap;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.Image;
import org.micromanager.data.Metadata;
import org.micromanager.data.ProcessorContext;

public class SingleCombinationProcessor {

   private final Studio studio_;
   private final LogManager log_;

   private final Coords coords_;
   private Image processedImage;

   private final String processorAlgo_;
   private final int numerOfImagesToProcess_;

   // Do we want to enable processing for this combinations of Z, Channel, Stage Position ?
   private final boolean processCombinations_;
   private final boolean isAnyChannelToAvoid_;

   private int current_frame_index;
   private int processed_frame_index;
   private Image[] bufferImages;
   private int currentBufferIndex;

   public SingleCombinationProcessor(Coords coords, Studio studio, String processorAlgo,
           int numerOfImagesToProcess, boolean processCombinations, boolean isAnyChannelToAvoid) {

      studio_ = studio;
      log_ = studio_.logs();

      coords_ = coords;

      processorAlgo_ = processorAlgo;
      numerOfImagesToProcess_ = numerOfImagesToProcess;
      processCombinations_ = processCombinations;
      isAnyChannelToAvoid_ = isAnyChannelToAvoid;

      current_frame_index = 0;
      processed_frame_index = 0;
      bufferImages = new Image[numerOfImagesToProcess_];

      for (int i = 0; i < numerOfImagesToProcess_; i++) {
         bufferImages[i] = null;
      }

      processedImage = null;

   }

   public void logMe() {
      log_.logMessage("Z : " + Integer.toString(coords_.getZ())
              + " | Channel : " + Integer.toString(coords_.getChannel())
              + " | Stage Position : " + Integer.toString(coords_.getStagePosition()));
   }

   void addImage(Image image, ProcessorContext context) {

      if (!processCombinations_) {
         context.outputImage(image);
         return;
      }

      currentBufferIndex = current_frame_index % numerOfImagesToProcess_;
      bufferImages[currentBufferIndex] = image;

      if (currentBufferIndex == (numerOfImagesToProcess_ - 1)) {

         try {
            // Process last `numerOfImagesToProcess_` images
            processBufferImages();
         } catch (Exception ex) {
            Logger.getLogger(FrameProcessor.class.getName()).log(Level.SEVERE, null, ex);
         }

         // Clean buffered images
         for (int i = 0; i < numerOfImagesToProcess_; i++) {
            bufferImages[i] = null;
         }

         // Add metadata to the processed image
         Metadata metadata = processedImage.getMetadata();
         PropertyMap userData = metadata.getUserData();
         if (userData != null) {
            userData = userData.copy().putBoolean("FrameProcessed", true).build();
            userData = userData.copy().putString("FrameProcessed-Operation", processorAlgo_).build();
            userData = userData.copy().putInt("FrameProcessed-StackNumber", numerOfImagesToProcess_).build();
            metadata = metadata.copy().userData(userData).build();
         }
         processedImage = processedImage.copyWithMetadata(metadata);

         // Add correct metadata if in acquisition mode
         if (studio_.acquisitions().isAcquisitionRunning() && !isAnyChannelToAvoid_) {
            Coords.CoordsBuilder builder = processedImage.getCoords().copy();
            builder.time(processed_frame_index);
            processedImage = processedImage.copyAtCoords(builder.build());
            processed_frame_index += 1;
         }

         // Output processed image
         context.outputImage(processedImage);

         // Clean processed image
         processedImage = null;
      }

      current_frame_index += 1;

   }

   public void clear() {
      for (int i = 0; i < numerOfImagesToProcess_; i++) {
         bufferImages[i] = null;
      }
      bufferImages = null;
   }

   public void processBufferImages() throws Exception {

      if (processorAlgo_.equals(FrameProcessorPlugin.PROCESSOR_ALGO_MEAN)) {
         meanProcessImages(false);
      } else if (processorAlgo_.equals(FrameProcessorPlugin.PROCESSOR_ALGO_SUM)) {
         meanProcessImages(true);
      } else if (processorAlgo_.equals(FrameProcessorPlugin.PROCESSOR_ALGO_MAX)) {
         extremaProcessImages("max");
      } else if (processorAlgo_.equals(FrameProcessorPlugin.PROCESSOR_ALGO_MIN)) {
         extremaProcessImages("min");
      } else {
         throw new Exception("FrameProcessor : Algorithm called " + processorAlgo_ + " is not implemented or not found.");
      }

   }

   public void meanProcessImages(boolean onlySum) {

      // Could be moved outside processImage() ?
      Image img = bufferImages[0];
      int bitDepth = img.getMetadata().getBitDepth();
      int width = img.getWidth();
      int height = img.getHeight();
      int bytesPerPixel = img.getBytesPerPixel();
      int numComponents = img.getNumComponents();
      Coords coords = img.getCoords();
      Metadata metadata = img.getMetadata();

      Object resultPixels = null;

      if (bytesPerPixel == 1) {

         // Create new array
         float[] newPixels = new float[width * height];
         byte[] newPixelsFinal = new byte[width * height];

         // Sum up all pixels from bufferImages
         for (int i = 0; i < numerOfImagesToProcess_; i++) {

            // Get current frame pixels
            img = bufferImages[i];
            byte[] imgPixels = (byte[]) img.getRawPixels();

            // Iterate over all pixels
            for (int index = 0; index < newPixels.length; index++) {
               newPixels[index] = (float) (newPixels[index] + (int) (imgPixels[index] & 0xff));
            }
         }

         // Divide by length to get the mean
         for (int index = 0; index < newPixels.length; index++) {
            if (onlySum) {
               newPixelsFinal[index] = (byte) (int) (newPixels[index]);
            } else {
               newPixelsFinal[index] = (byte) (int) (newPixels[index] / numerOfImagesToProcess_);
            }
         }

         resultPixels = newPixelsFinal;

      } else if (bytesPerPixel == 2) {

         // Create new array
         float[] newPixels = new float[width * height];
         short[] newPixelsFinal = new short[width * height];

         // Sum up all pixels from bufferImages
         for (int i = 0; i < numerOfImagesToProcess_; i++) {

            // Get current frame pixels
            img = bufferImages[i];
            short[] imgPixels = (short[]) img.getRawPixels();

            // Iterate over all pixels
            for (int index = 0; index < newPixels.length; index++) {
               newPixels[index] = (float) (newPixels[index] + (int) (imgPixels[index] & 0xffff));
            }
         }

         // Divide by length to get the mean
         for (int index = 0; index < newPixels.length; index++) {
            if (onlySum) {
               newPixelsFinal[index] = (short) (int) (newPixels[index]);
            } else {
               newPixelsFinal[index] = (short) (int) (newPixels[index] / numerOfImagesToProcess_);
            }
         }

         resultPixels = newPixelsFinal;

      }

      // Create the processed image
      processedImage = studio_.data().createImage(resultPixels, width, height,
              bytesPerPixel, numComponents, coords, metadata);

   }

   public void extremaProcessImages(String extremaType) throws Exception {

      // Could be moved outside processImage() ?
      Image img = bufferImages[0];
      int bitDepth = img.getMetadata().getBitDepth();
      int width = img.getWidth();
      int height = img.getHeight();
      int bytesPerPixel = img.getBytesPerPixel();
      int numComponents = img.getNumComponents();
      Coords coords = img.getCoords();
      Metadata metadata = img.getMetadata();

      Object resultPixels = null;

      if (bytesPerPixel == 1) {

         // Create new array
         float[] newPixels = new float[width * height];
         byte[] newPixelsFinal = new byte[width * height];

         float currentValue;
         float actualValue;

         // Init the new array
         if (extremaType.equals("max")) {
            for (int i = 0; i < newPixels.length; i++) {
               newPixels[i] = 0;
            }
         } else if (extremaType.equals("min")) {
            for (int i = 0; i < newPixels.length; i++) {
               newPixels[i] = Byte.MAX_VALUE;
            }
         } else {
            throw new Exception("FrameProcessor : Wrong extremaType " + extremaType);
         }

         // Iterate over all frames
         for (int i = 0; i < numerOfImagesToProcess_; i++) {

            // Get current frame pixels
            img = bufferImages[i];
            short[] imgPixels = (short[]) img.getRawPixels();

            // Iterate over all pixels
            for (int index = 0; index < newPixels.length; index++) {
               currentValue = (float) (int) (imgPixels[index] & 0xffff);
               actualValue = (float) newPixels[index];

               if (extremaType.equals("max")) {
                  newPixels[index] = (float) Math.max(currentValue, actualValue);
               } else if (extremaType.equals("min")) {
                  newPixels[index] = (float) Math.min(currentValue, actualValue);
               } else {
                  throw new Exception("FrameProcessor : Wrong extremaType " + extremaType);
               }
            }
         }

         // Convert to short
         for (int index = 0; index < newPixels.length; index++) {
            newPixelsFinal[index] = (byte) newPixels[index];
         }

         resultPixels = newPixelsFinal;

      } else if (bytesPerPixel == 2) {

         // Create new array
         float[] newPixels = new float[width * height];
         short[] newPixelsFinal = new short[width * height];

         float currentValue;
         float actualValue;

         // Init the new array
         if (extremaType.equals("max")) {
            for (int i = 0; i < newPixels.length; i++) {
               newPixels[i] = 0;
            }
         } else if (extremaType.equals("min")) {
            for (int i = 0; i < newPixels.length; i++) {
               newPixels[i] = Byte.MAX_VALUE;
            }
         } else {
            throw new Exception("FrameProcessor : Wrong extremaType " + extremaType);
         }

         // Iterate over all frames
         for (int i = 0; i < numerOfImagesToProcess_; i++) {

            // Get current frame pixels
            img = bufferImages[i];
            short[] imgPixels = (short[]) img.getRawPixels();

            // Iterate over all pixels
            for (int index = 0; index < newPixels.length; index++) {
               currentValue = (float) (int) (imgPixels[index] & 0xffff);
               actualValue = (float) newPixels[index];

               if (extremaType.equals("max")) {
                  newPixels[index] = (float) Math.max(currentValue, actualValue);
               } else if (extremaType.equals("min")) {
                  newPixels[index] = (float) Math.min(currentValue, actualValue);
               } else {
                  throw new Exception("FrameProcessor : Wrong extremaType " + extremaType);
               }
            }
         }

         // Convert to short
         for (int index = 0; index < newPixels.length; index++) {
            newPixelsFinal[index] = (short) newPixels[index];
         }

         resultPixels = newPixelsFinal;

      }

      // Create the processed image
      processedImage = studio_.data().createImage(resultPixels, width, height,
              bytesPerPixel, numComponents, coords, metadata);

   }
}
