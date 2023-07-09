package com.njdaeger.linker;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

public class Main {

    public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {
        System.out.println("Starting dynmap linker...");
        var result = new Linker(new LinkerSettings()).downloadWithRegions();

        System.out.println("Saving image...");
        try {
            // retrieve image
            //save the file with filename saved_mm-dd-yyyy_hh-MM-ss.png
            var dateString = java.time.LocalDateTime.now().toString().replace(":", "-").replace(".", "-");
            var outputfile = new File("saved_" + dateString + ".png");
            ImageIO.write(result, "png", outputfile);
        } catch (IOException e) {

        }
        System.out.println("Saved image.");
        System.out.println("Exiting.");
    }

}
