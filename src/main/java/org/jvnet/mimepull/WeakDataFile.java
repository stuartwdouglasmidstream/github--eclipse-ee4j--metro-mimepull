/*
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.jvnet.mimepull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Removing files based on this
 * <a href="https://www.oracle.com/technical-resources/articles/javase/finalization.html">article</a>
 *
 * @author Jitendra Kotamraju
 */
final class WeakDataFile extends WeakReference<DataFile> {

    private static final Logger LOGGER = Logger.getLogger(WeakDataFile.class.getName());
    private static int TIMEOUT = 10; //milliseconds
    //private static final int MAX_ITERATIONS = 2;
    private static ReferenceQueue<DataFile> refQueue = new ReferenceQueue<DataFile>();
    private static Queue<WeakDataFile> refList = new ConcurrentLinkedQueue<>();
    private File file;
    private final RandomAccessFile raf;
    private static boolean hasCleanUpExecutor = false;
    static {
        int delay = 10;
        try {
            delay = Integer.getInteger("org.jvnet.mimepull.delay", 10);
        } catch (SecurityException se) {
            if (LOGGER.isLoggable(Level.CONFIG)) {
                LOGGER.log(Level.CONFIG, "Cannot read ''{0}'' property, using defaults.",
                        new Object[] {"org.jvnet.mimepull.delay"});
            }
        }
        CleanUpExecutorFactory executorFactory = CleanUpExecutorFactory.newInstance();
        if (executorFactory!=null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Initializing clean up executor for MIMEPULL: {0}", executorFactory.getClass().getName());
            }
            ScheduledExecutorService scheduler = executorFactory.getScheduledExecutorService();
            scheduler.scheduleWithFixedDelay(new CleanupRunnable(), delay, delay, TimeUnit.SECONDS);
            hasCleanUpExecutor = true;
        }
    }

    WeakDataFile(DataFile df, File file) {
        super(df, refQueue);
        refList.add(this);
        this.file = file;
        try {
            raf = new RandomAccessFile(file, "rw");
        } catch(IOException ioe) {
            throw new MIMEParsingException(ioe);
        }
        if (!hasCleanUpExecutor) {
            drainRefQueueBounded();
        }
    }

    synchronized void read(long pointer, byte[] buf, int offset, int length ) {
        try {
            raf.seek(pointer);
            raf.readFully(buf, offset, length);
        } catch(IOException ioe) {
            throw new MIMEParsingException(ioe);
        }
    }

    synchronized long writeTo(long pointer, byte[] data, int offset, int length) {
        try {
            raf.seek(pointer);
            raf.write(data, offset, length);
            return raf.getFilePointer();    // Update pointer for next write
        } catch(IOException ioe) {
            throw new MIMEParsingException(ioe);
        }
    }

    void close() {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Deleting file = {0}", file.getName());
        }
        refList.remove(this);
        try {
            raf.close();
            boolean deleted = file.delete();
            if (!deleted) {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.log(Level.INFO, "File {0} was not deleted", file.getAbsolutePath());
                }
            }
        } catch(IOException ioe) {
            throw new MIMEParsingException(ioe);
        }
    }

    void renameTo(File f) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Moving file={0} to={1}", new Object[]{file, f});
        }
        refList.remove(this);
        try {
            raf.close();
            Path target = Files.move(file.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            boolean renamed = f.toPath().equals(target);
            if (!renamed) {
                if (LOGGER.isLoggable(Level.INFO)) {
                    throw new MIMEParsingException("File " + file.getAbsolutePath() +
                            " was not moved to " + f.getAbsolutePath());
                }
            }
            file = target.toFile();
        } catch(IOException ioe) {
            throw new MIMEParsingException(ioe);
        }

    }

    static void drainRefQueueBounded() {
        WeakDataFile weak;
        while (( weak = (WeakDataFile) refQueue.poll()) != null ) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Cleaning file = {0} from reference queue.", weak.file);
            }
            weak.close();
        }
    }

    private static class CleanupRunnable implements Runnable {

        @Override
        public void run() {
            try {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "Running cleanup task");
                }
                WeakDataFile weak = (WeakDataFile) refQueue.remove(TIMEOUT);
                while (weak != null) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, "Cleaning file = {0} from reference queue.", weak.file);
                    }
                    weak.close();
                    weak = (WeakDataFile) refQueue.remove(TIMEOUT);
                }
            } catch (InterruptedException e) {
            }
        }
    }
}
