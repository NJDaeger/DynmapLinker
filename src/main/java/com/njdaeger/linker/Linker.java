package com.njdaeger.linker;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.abs;

public class Linker {

//    private static int TILE_SIZE = 128;
//
//    private static String BASE_URL = "https://map.greenfieldmc.net";
//    private static String MAP = "flat";
//
//    //TOP LEFT
//    private static int CHUNK_X_TL = -80;
//    private static int CHUNK_Z_TL = -40;
//
//    //BOTTOM RIGHT
//    private static int CHUNK_X_BR = 100;
//    private static int CHUNK_Z_BR = 120;
//
//    private static int THREADS = 16;
//
//    private static int URL_Z_COUNT = 2;

    private static final AtomicInteger tilesDownloaded = new AtomicInteger(0);
    private static final AtomicInteger completedThreads = new AtomicInteger(0);


    private final String baseUrl;
    private final String world;
    private final String map;

    private final int tileSize;
    private final int zoomLevel;
    private final int chunkX_tl;
    private final int chunkZ_tl;
    private final int chunkX_br;
    private final int chunkZ_br;

    private final int allocatedThreads;

    private final int chunksPerTileEdge;
    private final int tilesPerRegionEdge;

    public Linker(LinkerSettings settings) throws InterruptedException {
        this.baseUrl = (String) settings.get("settings.map.baseUrl");
        this.world = (String) settings.get("settings.map.world");
        this.map = (String) settings.get("settings.map.map");

        var temp = (int) settings.get("settings.frame.chunkZ_topLeft");
        this.chunkZ_tl = -((int) settings.get("settings.frame.chunkZ_bottomRight"));
        this.chunkZ_br = -temp;
        this.chunkX_tl = (int) settings.get("settings.frame.chunkX_topLeft");
        this.chunkX_br = (int) settings.get("settings.frame.chunkX_bottomRight");
        this.tileSize = (int) settings.get("settings.frame.tileSize");
        this.zoomLevel = (int) settings.get("settings.frame.zoomLevel");

        this.allocatedThreads = (int) settings.get("settings.threadCount");

        this.chunksPerTileEdge = (int) Math.pow(2, zoomLevel);

        if ((int) settings.get("settings.frame.chunkX_bottomRight") % chunksPerTileEdge != 0) throw new IllegalArgumentException("Bottom right chunk X boundary not divisible by " + chunksPerTileEdge);
        else if ((int) settings.get("settings.frame.chunkZ_bottomRight") % chunksPerTileEdge != 0) throw new IllegalArgumentException("Bottom right chunk Z boundary not divisible by " + chunksPerTileEdge);
        else if ((int) settings.get("settings.frame.chunkX_topLeft") % chunksPerTileEdge != 0) throw new IllegalArgumentException("Top left chunk X boundary not divisible by " + chunksPerTileEdge);
        else if ((int) settings.get("settings.frame.chunkZ_topLeft") % chunksPerTileEdge != 0) throw new IllegalArgumentException("Top left chunk Z boundary not divisible by " + chunksPerTileEdge);

        var wholeFinalTileX = (chunkX_br - chunkX_tl) / chunksPerTileEdge;
        var wholeFinalTileZ = (chunkZ_br - chunkZ_tl) / chunksPerTileEdge;
        this.tilesPerRegionEdge = calculateRegionSize(wholeFinalTileX, wholeFinalTileZ);
        if (tilesPerRegionEdge == -1) throw new IllegalArgumentException("Could not calculate region size for " + wholeFinalTileX + " x " + wholeFinalTileZ);
        System.out.println("Region size: " + (wholeFinalTileX / tilesPerRegionEdge) + " x " + (wholeFinalTileZ / tilesPerRegionEdge));
        System.out.println("Tiles per region: " + tilesPerRegionEdge * tilesPerRegionEdge);
    }

    private static int calculateRegionSize(int tilesWide, int tilesHeight) {
        var factors = getFactors(tilesWide, tilesHeight);
        if (factors.size() == 0) {
            return -1;
        }
        //find the largest factor that is between 5 and 20
        factors.sort(Comparator.reverseOrder());
        for (int factor : factors) {
            if (factor <= 15 && factor >= 5) {
                return factor;
            }
        }
        return -1;
    }

    private static int gcd(int a, int b) {
        if (b == 0) {
            return a;
        }
        return gcd(b, a % b);
    }

    private static List<Integer> getFactors(int a, int b) {
        List<Integer> factors = new ArrayList<>();

        int gcd = gcd(a, b);

        for (int i = 1; i <= gcd; i++) {
            if (gcd % i == 0) {
                factors.add(i);
            }
        }

        return factors;
    }

    private List<TileDownload> getTileDownloadsForRegion(int chunkX_TL, int chunkZ_TL) {
        var queue = new ArrayList<TileDownload>();
        for (int x = chunkX_TL; x < (chunkX_TL + tilesPerRegionEdge * chunksPerTileEdge); x += chunksPerTileEdge) {
            for (int z = chunkZ_TL; z < (chunkZ_TL + tilesPerRegionEdge * chunksPerTileEdge); z += chunksPerTileEdge) {
                queue.add(new TileDownload(x, z));
            }
        }
        return queue;
    }

    public BufferedImage downloadWithRegions() throws InterruptedException {
        var queue = new Stack<Linker.RegionDownload>();
        var regionCount = 0;
        var completed = Collections.synchronizedList(new ArrayList<Linker.RegionDownload>());

        System.out.println("Generating regions...");
        for (int x = chunkX_tl; x < chunkX_br; x += chunksPerTileEdge * tilesPerRegionEdge) {
            for (int z = chunkZ_tl; z < chunkZ_br; z += chunksPerTileEdge * tilesPerRegionEdge) {
                queue.push(new Linker.RegionDownload(getTileDownloadsForRegion(x, z), x, z));
                regionCount++;
            }
        }
        System.out.println("Generated " + regionCount + " regions.");

        System.out.println("Processing regions...");
        var threads = new ArrayList<Thread>();
        int threadCount = 0;
        while (threadCount++ < allocatedThreads && threadCount <= regionCount) {
            threads.add(new Thread(() -> {
                while (!queue.isEmpty()) {
                    var region = queue.pop();
                    region.run();
                    synchronized (completed) {
                        completed.add(region);
                    }
                }
                completedThreads.incrementAndGet();
                Thread.currentThread().interrupt();
            }));
        }

        threads.forEach(Thread::start);
        while (completed.size() < regionCount) {
            Thread.sleep(100);
            System.out.printf("\rRegions completed: %d/%d [%d%%] | Threads completed: %d/%d | Tiles completed: %d/%d",
                    completed.size(), regionCount, (int) (((double) completed.size() / regionCount) * 100),
                    completedThreads.get(), threadCount - 1,
                    tilesDownloaded.get(), regionCount * tilesPerRegionEdge * tilesPerRegionEdge);
        }

        var resSizeWidth = chunkX_br + abs(chunkX_tl) ;
        var resSizeHeight = chunkZ_br + abs(chunkZ_tl);

        System.out.println("\nGenerating image...");
        var image = new BufferedImage((resSizeWidth * tileSize) / chunksPerTileEdge, (resSizeHeight * tileSize) / chunksPerTileEdge, BufferedImage.TYPE_INT_ARGB);
        Graphics2D stitched = image.createGraphics();
        for (RegionDownload region : completed) {
            var res = region.getResult();
            var x = region.chunkX_TL + abs(chunkX_tl);
            var z = region.chunkZ_TL + abs(chunkZ_tl);
            //draw image from bottom left
            stitched.drawImage(res, (x * tileSize) / chunksPerTileEdge, ((resSizeHeight * tileSize) / chunksPerTileEdge) - ((z * tileSize) / chunksPerTileEdge) - (tileSize * tilesPerRegionEdge), null);
        }
        stitched.dispose();
        System.out.println("Generated image.");

        return image;
    }

    public class TileDownload {

        private final int chunkX;
        private final int chunkZ;

        public TileDownload(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public BufferedImage getTile() {
            var image = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D stitched = image.createGraphics();
            stitched.setPaint(Color.BLACK);
            var downloaded = downloadTile(chunkX, chunkZ);
            if (downloaded == null) stitched.fillRect(0, 0, tileSize, tileSize);
            else stitched.drawImage(downloaded, 0, 0, downloaded.getWidth(), downloaded.getHeight(), null);
            stitched.dispose();
            return image;
        }

        private String getDownloadUrl(int chunkX, int chunkZ) {
            return baseUrl + "/standalone/MySQL_tiles.php?tile=" + world + "/" + map + "/0_0/" + (zoomLevel == 0 ? "" : ("z".repeat(zoomLevel) + "_")) + chunkX + "_" + chunkZ + ".png";
        }

        private BufferedImage downloadTile(int chunkX, int chunkZ) {
            var download = getDownloadUrl(chunkX, chunkZ);
            try {
                URL url = new URL(download);
                return ImageIO.read(url);
            } catch (IOException e) {
                try {
                    URL url = new URL(getDownloadUrl(chunkX, chunkZ));
                    return ImageIO.read(url);

                } catch (IOException e2) {
                    e2.printStackTrace();
                }
            }
            return null;
        }

    }

    public class RegionDownload implements Runnable {

        private final List<TileDownload> tiles;
        private final BufferedImage image;
        private final int chunkX_TL;
        private final int chunkZ_TL;

        public RegionDownload(List<TileDownload> tiles, int chunkX_TL, int chunkZ_TL) {
            this.chunkX_TL = chunkX_TL;
            this.chunkZ_TL = chunkZ_TL;
            this.tiles = tiles;

            var imgWidth = tilesPerRegionEdge * tileSize;
            var imgHeight = tilesPerRegionEdge * tileSize;
            this.image = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
        }

        public BufferedImage getResult() {
            return image;
        }

        @Override
        public void run() {
            Graphics2D stitched = image.createGraphics();
            stitched.setPaint(Color.BLACK);

            for (TileDownload tile : tiles) {
                var res = tile.getTile();
                var x = (tile.chunkX - chunkX_TL) / chunksPerTileEdge;
                var z = (tile.chunkZ - chunkZ_TL) / chunksPerTileEdge;
                var pixX = (x * tileSize);
                var pixY = (image.getHeight() - (z * tileSize)) - tileSize;
                stitched.drawImage(res, pixX, pixY, null);
                tilesDownloaded.incrementAndGet();
            }
            stitched.dispose();
        }
    }
}
