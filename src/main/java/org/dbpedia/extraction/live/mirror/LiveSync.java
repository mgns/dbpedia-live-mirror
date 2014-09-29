package org.dbpedia.extraction.live.mirror;

import org.dbpedia.extraction.live.mirror.changesets.Changeset;
import org.dbpedia.extraction.live.mirror.changesets.ChangesetExecutor;
import org.dbpedia.extraction.live.mirror.changesets.ChangesetCounter;
import org.dbpedia.extraction.live.mirror.download.LastDownloadDateManager;
import org.dbpedia.extraction.live.mirror.helper.Global;
import org.dbpedia.extraction.live.mirror.helper.UpdateStrategy;
import org.dbpedia.extraction.live.mirror.helper.Utils;
import org.dbpedia.extraction.live.mirror.sparul.JDBCPoolConnection;
import org.dbpedia.extraction.live.mirror.sparul.SPARULGenerator;
import org.dbpedia.extraction.live.mirror.sparul.SPARULVosExecutor;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: Mohamed Morsey
 * Date: 5/24/11
 * Time: 4:26 PM
 * This class is originally created from class defined in http://www.devdaily.com/java/edu/pj/pj010011
 * which is created by http://www.DevDaily.com
 */
public final class LiveSync {


    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(LiveSync.class);

    private static final String LAST_DOWNLOAD = "lastDownloadDate.dat";

    private static final int ERRORS_TO_ADVANCE = 3;

    private LiveSync() {
    }

    public static void main(String[] args) {

        if (args != null && args.length > 1) {
            logger.error("Incorrect arguments in Main. Must be zero or one of {Endless|Onetime}");
            System.exit(1);
        }
        UpdateStrategy strategy = UpdateStrategy.Endless; // default value
        if (args != null && args.length == 1) {
            try {
                strategy = UpdateStrategy.valueOf(args[0]);
            } catch (Exception e) {
                logger.error("Incorrect arguments in Main. Must be one of {Endless|Onetime}");
                System.exit(1);
            }
        }

        // setup change executor
        ChangesetExecutor changesetExecutor = new ChangesetExecutor(new SPARULVosExecutor(), new SPARULGenerator(Global.getOptions().get("LiveGraphURI")));

        // Variable init from ini file
        String updateServerAddress = Global.getOptions().get("UpdateServerAddress");
        String updatesDownloadFolder = Global.getOptions().get("UpdatesDownloadFolder");
        String addedTriplesFileExtension = Global.getOptions().get("addedTriplesFileExtension");
        String removedTriplesFileExtension = Global.getOptions().get("removedTriplesFileExtension");
        long delayInSeconds = Long.parseLong(Global.getOptions().get("LiveUpdateInterval")) * 1000l;

        // Set latest applied patch
        ChangesetCounter lastDownload = LastDownloadDateManager.getLastDownloadDate(LAST_DOWNLOAD);
        ChangesetCounter currentCounter = new ChangesetCounter(lastDownload);
        currentCounter.advancePatch(); // move to next patch (this one is already applied

        // Download last published file from server
        String lastPublishedFilename = Global.getOptions().get("lastPublishedFilename");
        String lastPublishFileRemote = updateServerAddress + lastPublishedFilename;
        Utils.downloadFile(lastPublishFileRemote, updatesDownloadFolder);
        String lastPublishFileLocal = updatesDownloadFolder + lastPublishedFilename;
        ChangesetCounter remoteCounter = new ChangesetCounter(Utils.getFileAsString(lastPublishFileLocal));

        int missing_urls = 0;
        while (true) {

            // when we are about to go beyond the remote published file
            if (currentCounter.compareTo(remoteCounter) > 0) {

                /**
                 * TODO between the app started (or last fetch of last published file)
                 * the remote counter may be advanced but we don't take this into consideration here
                 * probably should re-download in a temp counter, check if different and continue without sleep
                 */

                // in case of onetime run, exit
                if (strategy.equals(UpdateStrategy.Onetime)) {
                    logger.info("Finished the One-Time update, exiting...");
                    break;
                }

                // sleep + download new file & continue
                logger.info("Up-to-date with last published changeset, sleeping for a while ;)");
                try {
                    Thread.sleep(delayInSeconds);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // code duplication
                Utils.downloadFile(lastPublishFileRemote, updatesDownloadFolder);
                remoteCounter = new ChangesetCounter(Utils.getFileAsString(lastPublishFileLocal));

                //now we have an updated remote counter so next time this block will not run (if the updates are running)
                continue;

            }


            String addedTriplesURL = updateServerAddress + currentCounter.getFormattedFilePath() + addedTriplesFileExtension;
            String deletedTriplesURL = updateServerAddress + currentCounter.getFormattedFilePath() + removedTriplesFileExtension;

            // changesets default to empty
            List<String> triplesToDelete = Arrays.asList();
            List<String> triplesToAdd = Arrays.asList();

            //Download and decompress the file of deleted triples
            String addedCompressedDownloadedFile = Utils.downloadFile(addedTriplesURL, updatesDownloadFolder);
            String deletedCompressedDownloadedFile = Utils.downloadFile(deletedTriplesURL, updatesDownloadFolder);

            // Check for errors before proceeding
            if (addedCompressedDownloadedFile == null && deletedCompressedDownloadedFile == null) {
                missing_urls++;
                if (missing_urls >= ERRORS_TO_ADVANCE) {
                    // advance hour / day / month or year
                    currentCounter.advanceHour();
                }
                continue;
            }
            // URL works, reset missing URLs
            missing_urls = 0;


            if (deletedCompressedDownloadedFile != null) {

                String decompressedDeletedNTriplesFile = Utils.decompressGZipFile(deletedCompressedDownloadedFile);
                triplesToDelete = Utils.getTriplesFromFile(decompressedDeletedNTriplesFile);

                Utils.deleteFile(decompressedDeletedNTriplesFile);

                //Reset the number of failed trails, since the file is found and downloaded successfully
                Global.setNumberOfSuccessiveFailedTrails(0);
            }


            if (addedCompressedDownloadedFile != null) {
                String decompressedAddedNTriplesFile = Utils.decompressGZipFile(addedCompressedDownloadedFile);
                triplesToAdd = Utils.getTriplesFromFile(decompressedAddedNTriplesFile);

                Utils.deleteFile(decompressedAddedNTriplesFile);

                //Reset the number of failed trails, since the file is found and downloaded successfully
                Global.setNumberOfSuccessiveFailedTrails(0);
            }

            Changeset changeset = new Changeset(currentCounter.toString(), triplesToAdd, triplesToDelete);
            changesetExecutor.applyChangeset(changeset);


            // save last processed date
            LastDownloadDateManager.writeLastDownloadDate(LAST_DOWNLOAD, currentCounter.toString());

            // advance to the next patch
            currentCounter.advancePatch();

        }

        JDBCPoolConnection.shutdown();

    }  // end of main

}
