package com.p3212.mapproxy.controller;

import com.p3212.mapproxy.object.TileKey;
import com.p3212.mapproxy.repository.TilesRepository;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.ServletContextAware;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import javax.servlet.ServletContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Controller
public class ReactiveRequestController implements ServletContextAware {

    private ServletContext servletContext;

    @Override
    public void setServletContext (ServletContext context) {
        this.servletContext = context;
    }

    @GetMapping(value = "/{first}/{second}/{third}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getMapTile(@PathVariable(value = "first") int x,
               @PathVariable(value = "second") int y, @PathVariable(value = "third") int z) {
        //key object is an 'id' of a pile
        TileKey key = new TileKey();
        key.setX(x);
        key.setY(y);
        key.setZ(z);

        //Check if image was already loaded and cached
        if (TilesRepository.isCached(key)) {
            try {
                //Getting pile from disk
                InputStream in = servletContext.getResourceAsStream("./"+x+"_"+y+"_"+z+".png");
                return ResponseEntity.ok(IOUtils.toByteArray(in));
            } catch (IOException error) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
        //If pile wasn't cached yet
        else {
            try {
                //If another thread has already requested for the same pile
                //Then a Future object for this pile was created in TilesRepository
                //This thread waits for the pile to be loaded
                if (TilesRepository.isPileBeingLoaded(key)) {
                    BufferedImage image;
                    image = TilesRepository.getFuture(key).get();
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", outputStream);
                    return ResponseEntity.ok(outputStream.toByteArray());

                } else {
                    //If this is the first request for a specific pile
                    //Requesting pile from openStreetMap
                    //Using Future
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<BufferedImage> imageFuture = executor.submit(() ->
                            ImageIO.read(new URL("https://a.tile.openstreetmap.org/" + x + "/" + y + "/" + z + ".png")));

                    //Adding a Future object to repository
                    //So that if a new request for the same pile is received
                    //No additional request to openStreetMap will be sent
                    TilesRepository.addLoadingPile(key, imageFuture);
                    BufferedImage image = imageFuture.get();

                    //Creating a Flux so that pile will be sent to user
                    //And cached on disk simultaneously
                    Flux.just(image)
                            .subscribeOn(Schedulers.newSingle("cachingThread"))
                            .subscribe((data) -> {
                                try {
                                    //caching tile on disk
                                    File picFile = new File("./" + x + "_" + y + "_" + z + ".png");
                                    ImageIO.write(image, "png", picFile);

                                    //Add info about caching
                                    TilesRepository.addCachedTile(key);

                                    //Remove future object since pile was already cached on disk
                                    TilesRepository.removeLoaded(key);
                                } catch (IOException error) {
                                    Logger logger = LoggerFactory.getLogger(ReactiveRequestController.class);
                                    logger.error(error.getMessage());
                                }
                            });

                    //Sending tile to user concurrently with caching
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", outputStream);
                    return ResponseEntity.ok(outputStream.toByteArray());
                }
            } catch (IOException e) {
                return ResponseEntity.notFound().build();
            } catch (InterruptedException | ExecutionException error) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
    }


}
