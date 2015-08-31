
package edu.harvard.iq.dataverse;

// TODO: 
// clean up the imports. -- L.A. 4.2

import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.providers.builtin.BuiltinUserServiceBean;
import edu.harvard.iq.dataverse.authorization.users.ApiToken;
import edu.harvard.iq.dataverse.authorization.users.User;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.datavariable.VariableServiceBean;
import edu.harvard.iq.dataverse.engine.command.Command;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.impl.CreateDatasetCommand;
import edu.harvard.iq.dataverse.engine.command.impl.CreateGuestbookResponseCommand;
import edu.harvard.iq.dataverse.engine.command.impl.DeaccessionDatasetVersionCommand;
import edu.harvard.iq.dataverse.engine.command.impl.DeleteDatasetVersionCommand;
import edu.harvard.iq.dataverse.engine.command.impl.DestroyDatasetCommand;
import edu.harvard.iq.dataverse.engine.command.impl.LinkDatasetCommand;
import edu.harvard.iq.dataverse.engine.command.impl.PublishDatasetCommand;
import edu.harvard.iq.dataverse.engine.command.impl.PublishDataverseCommand;
import edu.harvard.iq.dataverse.engine.command.impl.UpdateDatasetCommand;
import edu.harvard.iq.dataverse.ingest.IngestRequest;
import edu.harvard.iq.dataverse.ingest.IngestServiceBean;
import edu.harvard.iq.dataverse.metadataimport.ForeignMetadataImportServiceBean;
import edu.harvard.iq.dataverse.search.FacetCategory;
import edu.harvard.iq.dataverse.search.FileView;
import edu.harvard.iq.dataverse.search.SearchFilesServiceBean;
import edu.harvard.iq.dataverse.search.SolrSearchResult;
import edu.harvard.iq.dataverse.search.SortBy;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.BundleUtil;
import edu.harvard.iq.dataverse.util.FileSortFieldAndOrder;
import edu.harvard.iq.dataverse.util.JsfHelper;
import static edu.harvard.iq.dataverse.util.JsfHelper.JH;
import edu.harvard.iq.dataverse.util.StringUtil;
import edu.harvard.iq.dataverse.util.SystemConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.faces.event.ActionEvent;
import javax.faces.event.ValueChangeEvent;
import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.UploadedFile;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonArray;
import javax.json.JsonReader;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolation;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.primefaces.context.RequestContext;
import java.text.DateFormat;
import javax.faces.model.SelectItem;
import java.util.logging.Level;

/**
 *
 * @author Leonid Andreev
 */
@ViewScoped
@Named("EditDatafilesPage")
public class EditDatafilesPage implements java.io.Serializable {

    private static final Logger logger = Logger.getLogger(EditDatafilesPage.class.getCanonicalName());
    private FileView fileView;

    @EJB
    DatasetServiceBean datasetService;
    @EJB
    DatasetVersionServiceBean datasetVersionService;
    @EJB
    DataFileServiceBean datafileService;
    @EJB
    PermissionServiceBean permissionService;
    @EJB
    IngestServiceBean ingestService;
    @EJB
    EjbDataverseEngine commandEngine;
    @Inject
    DataverseSession session;
    @EJB
    UserNotificationServiceBean userNotificationService;
    @EJB
    SettingsServiceBean settingsService;
    @EJB
    AuthenticationServiceBean authService;
    @EJB
    SystemConfig systemConfig;
    @EJB
    DataverseLinkingServiceBean dvLinkingService;
    @Inject
    DataverseRequestServiceBean dvRequestService;

    private final DateFormat displayDateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM);

    private Dataset dataset = null;
    
    private String selectedFileIdsString = null; 
    private List<Long> selectedFileIdsList = new ArrayList<>(); 
    private List<FileMetadata> fileMetadatas;

    
    private Long ownerId;
    private Long versionId;
    private final List<DataFile> newFiles = new ArrayList();
    private DatasetVersion workingVersion;
    private String dropBoxSelection = "";
    private String displayCitation;
    
    private String persistentId;
    private String protocol = "";
    private String authority = "";
    private String separator = "";

    // Used to store results of permissions checks
    private final Map<String, Boolean> datasetPermissionMap = new HashMap<>(); // { Permission human_name : Boolean }

    private Long maxFileUploadSizeInBytes = null;

    public String getSelectedFileIds() {
        return selectedFileIdsString;
    }
    
    public void setSelectedFileIds(String selectedFileIds) {
        selectedFileIdsString = selectedFileIds;
    }
    
    public List<FileMetadata> getFileMetadatas() {
        if (fileMetadatas != null) {
            logger.info("Returning a list of "+fileMetadatas.size()+" file metadatas.");
        } else {
            logger.info("File metadatas list hasn't been initialized yet.");
        }
        return fileMetadatas;
    }
    
    public void setFileMetadatas(List<FileMetadata> fileMetadatas) {
        this.fileMetadatas = fileMetadatas;
    }
    
    /*
        Any settings, such as the upload size limits, should be saved locally - 
        so that the db doesn't get hit repeatedly. (this setting is initialized 
        in the init() method)
    
        This may be "null", signifying unlimited download size.
    */
    
    public Long getMaxFileUploadSizeInBytes(){
        return this.maxFileUploadSizeInBytes;
    }
    
    public boolean isUnlimitedUploadFileSize(){
        
        if (this.maxFileUploadSizeInBytes == null){
            return true;
        }
        return false;
    }
    

    /**
     * Check Dataset related permissions
     * 
     * @param permissionToCheck
     * @return 
     */
    public boolean doesSessionUserHaveDataSetPermission(Permission permissionToCheck){
        if (permissionToCheck == null){
            return false;
        }
               
        String permName = permissionToCheck.getHumanName();
       
        // Has this check already been done? 
        // 
        if (this.datasetPermissionMap.containsKey(permName)){
            // Yes, return previous answer
            return this.datasetPermissionMap.get(permName);
        }
        
        // Check the permission
        //
        boolean hasPermission = this.permissionService.userOn(this.session.getUser(), this.dataset).has(permissionToCheck);

        // Save the permission
        this.datasetPermissionMap.put(permName, hasPermission);
        
        // return true/false
        return hasPermission;
    }
    
    public void reset() {
        // ?
    }

    public String getGlobalId() {
        return persistentId;
    }
        
    public String getPersistentId() {
        return persistentId;
    }

    public void setPersistentId(String persistentId) {
        this.persistentId = persistentId;
    }

    public String getDisplayCitation() {
        //displayCitation = dataset.getCitation(false, workingVersion);
        return displayCitation;
    }

    public void setDisplayCitation(String displayCitation) {
        this.displayCitation = displayCitation;
    }

    public String getDropBoxSelection() {
        return dropBoxSelection;
    }

    public String getDropBoxKey() {
        // Site-specific DropBox application registration key is configured 
        // via a JVM option under glassfish.
        //if (true)return "some-test-key";  // for debugging

        String configuredDropBoxKey = System.getProperty("dataverse.dropbox.key");
        if (configuredDropBoxKey != null) {
            return configuredDropBoxKey;
        }
        return "";
    }

    public void setDropBoxSelection(String dropBoxSelection) {
        this.dropBoxSelection = dropBoxSelection;
    }

    public Dataset getDataset() {
        return dataset;
    }

    public void setDataset(Dataset dataset) {
        this.dataset = dataset;
    }

    public DatasetVersion getWorkingVersion() {
        return workingVersion;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public String init() {
        String nonNullDefaultIfKeyNotFound = "";
        this.maxFileUploadSizeInBytes = systemConfig.getMaxFileUploadSize();
        
        protocol = settingsService.getValueForKey(SettingsServiceBean.Key.Protocol, nonNullDefaultIfKeyNotFound);
        authority = settingsService.getValueForKey(SettingsServiceBean.Key.Authority, nonNullDefaultIfKeyNotFound);
        separator = settingsService.getValueForKey(SettingsServiceBean.Key.DoiSeparator, nonNullDefaultIfKeyNotFound);
        
        if (versionId != null) {
          
            
           DatasetVersionServiceBean.RetrieveDatasetVersionResponse retrieveDatasetVersionResponse = null;
           
            // -------------------------------------------------
            // Set the workingVersion and Dataset by version id:
            // -------------------------------------------------
           
            
            retrieveDatasetVersionResponse = datasetVersionService.retrieveDatasetVersionByVersionId(versionId);                     
               
            if (retrieveDatasetVersionResponse == null){
               return "/404.xhtml";
            }

            DatasetVersion retrievedVersion = retrieveDatasetVersionResponse.getDatasetVersion();
            if (retrievedVersion != null) {
                dataset = retrievedVersion.getDataset();
                if (dataset != null) {
                    workingVersion = dataset.getEditVersion();
                }
            }

            // Is the DatasetVersion or Dataset null?
            //
            if (workingVersion == null || dataset == null) {
                return "/404.xhtml";
            }

            if (!workingVersion.isWorkingCopy()) {
                // TODO: should be doing something better than this...
                return "/404.xhtml";
            }

            // Is the Dataset harvested? (because we don't allow editing of harvested 
            // files!)
            if (dataset.isHarvested()) {
                return "/404.xhtml";
            }

            // If this is not a draft version, and/or the user doesn't have permission
            // to edit this version - send them to the login page, or straight to 
            // 403 - as appropriate: 
            /*
             if (!(workingVersion.isReleased() || workingVersion.isDeaccessioned()) && !permissionService.on(dataset).has(Permission.ViewUnpublishedDataset)) {
             if(!session.getUser().isAuthenticated()){
             return "/loginpage.xhtml" + DataverseHeaderFragment.getRedirectPage();
             } else {
             return "/403.xhtml"; //SEK need a new landing page if user is already logged in but lacks permission
             }               
             }
             */
           // TODO: still needed?
           /*
             if (!retrieveDatasetVersionResponse.wasRequestedVersionRetrieved()){
             //msg("checkit " + retrieveDatasetVersionResponse.getDifferentVersionMessage());
             JsfHelper.addWarningMessage(retrieveDatasetVersionResponse.getDifferentVersionMessage());//JH.localize("dataset.message.metadataSuccess"));
             }*/
            ownerId = dataset.getOwner().getId();
            displayCitation = dataset.getCitation(true, workingVersion);


        } else {
            return "/404.xhtml";
        }
        
        if (selectedFileIdsString != null) {
            String[] ids = selectedFileIdsString.split(",");
            
            for (String id : ids) {
                Long test = null;
                try {
                    test = new Long(id);
                } catch (NumberFormatException nfe) {
                    // do nothing...
                    test = null; 
                }
                if (test != null) {
                    selectedFileIdsList.add(test);
                }
            }
        }
        
        if (selectedFileIdsList.size() < 1) {
            logger.info("No numeric file ids supplied to the page. Redirecting to the 404 page.");
            // If no valid file IDs specified, send them to the 404 page...
            return "/404.xhtml";
        }
        
        logger.info("The page is called with " + selectedFileIdsList.size() + " file ids.");
        
        populateFileMetadatas();
        
        // and if no filemetadatas can be found for the specified file ids 
        // and version id - same deal, send them to the "not found" page. 
        // (at least for now; ideally, we probably want to show them a page 
        // with a more informative error message; something alonog the lines 
        // of - could not find the files for the ids specified; or, these 
        // datafiles are not present in the version specified, etc.
        
        if (fileMetadatas.size() < 1) {
            return "/404.xhtml";
        } 

        return null;
    }
    
 
    public void edit() { /* TODO: is it needed? */

        workingVersion = dataset.getEditVersion();

        JH.addMessage(FacesMessage.SEVERITY_INFO, JH.localize("dataset.message.editFiles"));
            // FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, "Upload + Edit Dataset Files", " - You can drag and drop your files from your desktop, directly into the upload widget."));

    }

    private List<FileMetadata> selectedFiles; // = new ArrayList<>();

    public List<FileMetadata> getSelectedFiles() {
        return selectedFiles;
    }

    public void setSelectedFiles(List<FileMetadata> selectedFiles) {
        this.selectedFiles = selectedFiles;
    }
    
    // helper Method
    public String getSelectedFilesIdsString() {        
        String downloadIdString = "";
        for (FileMetadata fmd : this.selectedFiles){
            if (!StringUtil.isEmpty(downloadIdString)) {
                downloadIdString += ",";
            }
            downloadIdString += fmd.getDataFile().getId();
        }
        return downloadIdString;
      
    }

    
    /* what are these for? */
    List<FileMetadata> previouslyRestrictedFiles = null;
    

    public void restrictFiles(boolean restricted) {
        // since we are restricted files, first set the previously restricted file list, so we can compare for
        // determinin whether to show the access popup
        if (previouslyRestrictedFiles == null) {
            previouslyRestrictedFiles = new ArrayList();
            for (FileMetadata fmd : workingVersion.getFileMetadatas()) {
                if (fmd.isRestricted()) {
                    previouslyRestrictedFiles.add(fmd);
                }
            }
        }        
        
        String fileNames = null;
        for (FileMetadata fmd : this.getSelectedFiles()) {
            if (restricted && !fmd.isRestricted()) {
                // collect the names of the newly-restrticted files, 
                // to show in the success message:
                if (fileNames == null) {
                    fileNames = fmd.getLabel();
                } else {
                    fileNames = fileNames.concat(fmd.getLabel());
                }
            }
            fmd.setRestricted(restricted);
        }
        if (fileNames != null) {
            String successMessage = JH.localize("file.restricted.success");
            logger.fine(successMessage);
            successMessage = successMessage.replace("{0}", fileNames);
            JsfHelper.addFlashMessage(successMessage);    
        }
    } 

    public int getRestrictedFileCount() {
        int restrictedFileCount = 0;
        for (FileMetadata fmd : workingVersion.getFileMetadatas()) {
            if (fmd.isRestricted()) {
                restrictedFileCount++;
            }
        }

        return restrictedFileCount;
    }

    private List<FileMetadata> filesToBeDeleted = new ArrayList();

    public void deleteFiles() {

        String fileNames = null;
        for (FileMetadata fmd : this.getSelectedFiles()) {
                // collect the names of the newly-restrticted files, 
            // to show in the success message:
            if (fileNames == null) {
                fileNames = fmd.getLabel();
            } else {
                fileNames = fileNames.concat(fmd.getLabel());
            }
        }

        for (FileMetadata markedForDelete : selectedFiles) {
            if (markedForDelete.getId() != null) {
                // the file already exists as part of this dataset
                // so all we remove is the file from the fileMetadatas (for display)
                // and let the delete be handled in the command (by adding it to the filesToBeDeleted list
                dataset.getEditVersion().getFileMetadatas().remove(markedForDelete);
                filesToBeDeleted.add(markedForDelete);
            } else {
                // the file was just added during this step, so in addition to 
                // removing it from the fileMetadatas (for display), we also remove it from 
                // the newFiles list and the dataset's files, so it won't get uploaded at all
                Iterator fmit = dataset.getEditVersion().getFileMetadatas().iterator();
                while (fmit.hasNext()) {
                    FileMetadata fmd = (FileMetadata) fmit.next();
                    if (markedForDelete.getDataFile().getStorageIdentifier().equals(fmd.getDataFile().getStorageIdentifier())) {
                        fmit.remove();
                        break;
                    }
                }
                
                Iterator<DataFile> dfIt = dataset.getFiles().iterator();
                while (dfIt.hasNext()) {
                    DataFile dfn = dfIt.next();
                    if (markedForDelete.getDataFile().getStorageIdentifier().equals(dfn.getStorageIdentifier())) {
                        
                        // Before we remove the file from the list and forget about 
                        // it:
                        // The physical uploaded file is still sitting in the temporary
                        // directory. If it were saved, it would be moved into its 
                        // permanent location. But since the user chose not to save it,
                        // we have to delete the temp file too. 
                        // 
                        // Eventually, we will likely add a dedicated mechanism
                        // for managing temp files, similar to (or part of) the storage 
                        // access framework, that would allow us to handle specialized
                        // configurations - highly sensitive/private data, that 
                        // has to be kept encrypted even in temp files, and such. 
                        // But for now, we just delete the file directly on the 
                        // local filesystem: 

                        try {
                            Files.delete(Paths.get(ingestService.getFilesTempDirectory() + "/" + dfn.getStorageIdentifier()));
                        } catch (IOException ioEx) {
                            // safe to ignore - it's just a temp file. 
                            logger.warning("Failed to delete temporary file " + ingestService.getFilesTempDirectory() + "/" + dfn.getStorageIdentifier());
                        }
                        
                        dfIt.remove();

                    }
                }
                
                

                Iterator<DataFile> nfIt = newFiles.iterator();
                while (nfIt.hasNext()) {
                    DataFile dfn = nfIt.next();
                    if (markedForDelete.getDataFile().getStorageIdentifier().equals(dfn.getStorageIdentifier())) {
                        nfIt.remove();
                    }
                }                
                
            }
        }

     
        if (fileNames != null) {
            String successMessage = JH.localize("file.deleted.success");
            logger.fine(successMessage);
            successMessage = successMessage.replace("{0}", fileNames);
            JsfHelper.addFlashMessage(successMessage);
        }
    }

    public String save() {
        // Validate
        Set<ConstraintViolation> constraintViolations = workingVersion.validate();
        if (!constraintViolations.isEmpty()) {
             //JsfHelper.addFlashMessage(JH.localize("dataset.message.validationError"));
             JH.addMessage(FacesMessage.SEVERITY_ERROR, JH.localize("dataset.message.validationError"));
            //FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Validation Error", "See below for details."));
            return "";
        }
               


        // One last check before we save the files - go through the newly-uploaded 
        // ones and modify their names so that there are no duplicates. 
        // (but should we really be doing it here? - maybe a better approach to do it
        // in the ingest service bean, when the files get uploaded.)
        // Finally, save the files permanently: 
        ingestService.addFiles(workingVersion, newFiles);

        // Use the API to save the dataset: 
        Command<Dataset> cmd;
        try {
            /* -- ?
            if (editMode == EditMode.CREATE) {
                workingVersion.setLicense(DatasetVersion.License.CC0);
                if ( selectedTemplate != null ) {
                    if ( session.getUser().isAuthenticated() ) {
                        cmd = new CreateDatasetCommand(dataset, dvRequestService.getDataverseRequest(), false, null, selectedTemplate); 
                    } else {
                        JH.addMessage(FacesMessage.SEVERITY_FATAL, JH.localize("dataset.create.authenticatedUsersOnly"));
                        return null;
                    }
                } else {
                   cmd = new CreateDatasetCommand(dataset, dvRequestService.getDataverseRequest());
                }
                
            } else { */
                cmd = new UpdateDatasetCommand(dataset, dvRequestService.getDataverseRequest(), filesToBeDeleted);
            /*}*/
            dataset = commandEngine.submit(cmd);
            /* if (editMode == EditMode.CREATE) {
                if (session.getUser() instanceof AuthenticatedUser) {
                    userNotificationService.sendNotification((AuthenticatedUser) session.getUser(), dataset.getCreateDate(), UserNotification.Type.CREATEDS, dataset.getLatestVersion().getId());
                }
            }*/
        } catch (EJBException ex) {
            StringBuilder error = new StringBuilder();
            error.append(ex).append(" ");
            error.append(ex.getMessage()).append(" ");
            Throwable cause = ex;
            while (cause.getCause()!= null) {
                cause = cause.getCause();
                error.append(cause).append(" ");
                error.append(cause.getMessage()).append(" ");
            }
            logger.log(Level.FINE, "Couldn''t save dataset: {0}", error.toString());
            populateDatasetUpdateFailureMessage();
            return null;
        } catch (CommandException ex) {
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Dataset Save Failed", " - " + ex.toString()));
            logger.severe(ex.getMessage());
            populateDatasetUpdateFailureMessage();
            return null;
        }
        newFiles.clear();
        
        JsfHelper.addSuccessMessage(JH.localize("dataset.message.filesSuccess"));
        

        // Call Ingest Service one more time, to 
        // queue the data ingest jobs for asynchronous execution: 
        ingestService.startIngestJobs(dataset, (AuthenticatedUser) session.getUser());

        return returnToDatasetOnly();
    }
    
    private void populateDatasetUpdateFailureMessage(){
            
                JH.addMessage(FacesMessage.SEVERITY_FATAL, JH.localize("dataset.message.filesFailure"));
    }
    
    
    
    
    
    private String returnToDatasetOnly(){
         dataset = datasetService.find(dataset.getId());
         return "/dataset.xhtml?persistentId=" + dataset.getGlobalId()  +  "&faces-redirect=true";       
    }
    
    public String cancel() {
        return  returnToDatasetOnly();
    }

    public boolean isDuplicate(FileMetadata fileMetadata) {
        String thisMd5 = fileMetadata.getDataFile().getmd5();
        if (thisMd5 == null) {
            return false;
        }

        Map<String, Integer> MD5Map = new HashMap<String, Integer>();

        // TODO: 
        // think of a way to do this that doesn't involve populating this 
        // map for every file on the page? 
        // man not be that much of a problem, if we paginate and never display 
        // more than a certain number of files... Still, needs to be revisited
        // before the final 4.0. 
        // -- L.A. 4.0
        Iterator<FileMetadata> fmIt = workingVersion.getFileMetadatas().iterator();
        while (fmIt.hasNext()) {
            FileMetadata fm = fmIt.next();
            String md5 = fm.getDataFile().getmd5();
            if (md5 != null) {
                if (MD5Map.get(md5) != null) {
                    MD5Map.put(md5, MD5Map.get(md5).intValue() + 1);
                } else {
                    MD5Map.put(md5, 1);
                }
            }
        }

        return MD5Map.get(thisMd5) != null && MD5Map.get(thisMd5).intValue() > 1;
    }

    private HttpClient getClient() {
        // TODO: 
        // cache the http client? -- L.A. 4.0 alpha
        return new HttpClient();
    }

    public boolean showFileUploadFileComponent(){
        
        //if ((this.editMode == this.editMode.FILE) || (this.editMode == this.editMode.CREATE)){
           return true;
        //}
        //return false;
    }
    
    

    /**
     * Download a file from drop box
     * 
     * @param fileLink
     * @return 
     */
    private InputStream getDropBoxInputStream(String fileLink, GetMethod dropBoxMethod){
        
        if (fileLink == null){
            return null;
        }
        
        // -----------------------------------------------------------
        // Make http call, download the file: 
        // -----------------------------------------------------------
        int status = 0;
        //InputStream dropBoxStream = null;

        try {
            status = getClient().executeMethod(dropBoxMethod);
            if (status == 200) {
                return dropBoxMethod.getResponseBodyAsStream();
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Failed to access DropBox url: {0}!", fileLink);
            return null;
        } 

        logger.log(Level.WARNING, "Failed to get DropBox InputStream for file: {0}", fileLink);
        return null;
    } // end: getDropBoxInputStream
                  
    
    /**
     * Using information from the DropBox choose, ingest the chosen files
     *  https://www.dropbox.com/developers/dropins/chooser/js
     * 
     * @param e 
     */
    public void handleDropBoxUpload(ActionEvent event) {
        
        logger.info("handleDropBoxUpload");
        // -----------------------------------------------------------
        // Read JSON object from the output of the DropBox Chooser: 
        // -----------------------------------------------------------
        JsonReader dbJsonReader = Json.createReader(new StringReader(dropBoxSelection));
        JsonArray dbArray = dbJsonReader.readArray();
        dbJsonReader.close();

        // -----------------------------------------------------------
        // Iterate through the Dropbox file information (JSON)
        // -----------------------------------------------------------
        DataFile dFile = null;
        GetMethod dropBoxMethod = null;
        for (int i = 0; i < dbArray.size(); i++) {
            JsonObject dbObject = dbArray.getJsonObject(i);

            // -----------------------------------------------------------
            // Parse information for a single file
            // -----------------------------------------------------------
            String fileLink = dbObject.getString("link");
            String fileName = dbObject.getString("name");
            int fileSize = dbObject.getInt("bytes");

            logger.info("DropBox url: " + fileLink + ", filename: " + fileName + ", size: " + fileSize);


            /* ----------------------------
                Check file size
                - Max size NOT specified in db: default is unlimited
                - Max size specified in db: check too make sure file is within limits
            // ---------------------------- */
            if ((!this.isUnlimitedUploadFileSize())&&(fileSize > this.getMaxFileUploadSizeInBytes())){
                String warningMessage = "Dropbox file \"" + fileName + "\" exceeded the limit of " + fileSize + " bytes and was not uploaded.";
                //msg(warningMessage);
                FacesContext.getCurrentInstance().addMessage(event.getComponent().getClientId(), new FacesMessage(FacesMessage.SEVERITY_ERROR, "upload failure", warningMessage));
                continue; // skip to next file, and add error mesage
            }

            
            dFile = null;
            dropBoxMethod = new GetMethod(fileLink);

            // -----------------------------------------------------------
            // Download the file
            // -----------------------------------------------------------
            InputStream dropBoxStream = this.getDropBoxInputStream(fileLink, dropBoxMethod);
            if (dropBoxStream==null){
                logger.severe("Could not retrieve dropgox input stream for: " + fileLink);
                continue;  // Error skip this file
            }
            
            List<DataFile> datafiles = new ArrayList<DataFile>(); 
            
            // -----------------------------------------------------------
            // Send it through the ingest service
            // -----------------------------------------------------------
            try {
                // Note: A single file may be unzipped into multiple files
                datafiles = ingestService.createDataFiles(workingVersion, dropBoxStream, fileName, "application/octet-stream");     
                
            } catch (IOException ex) {
                this.logger.log(Level.SEVERE, "Error during ingest of DropBox file {0} from link {1}", new Object[]{fileName, fileLink});
                continue;
            }finally {
                // -----------------------------------------------------------
                // release connection for dropBoxMethod
                // -----------------------------------------------------------
                
                if (dropBoxMethod != null) {
                    dropBoxMethod.releaseConnection();
                }
                
                // -----------------------------------------------------------
                // close the  dropBoxStream
                // -----------------------------------------------------------
                try {
                    dropBoxStream.close();
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Failed to close the dropBoxStream for file: {0}", fileLink);
                }
            }
            
            if (datafiles == null){
                this.logger.log(Level.SEVERE, "Failed to create DataFile for DropBox file {0} from link {1}", new Object[]{fileName, fileLink});
                continue;
            }else{    
                // -----------------------------------------------------------
                // Check if there are duplicate files or ingest warnings
                // -----------------------------------------------------------
                String warningMessage = processUploadedFileList(datafiles);
                logger.info("Warning message during upload: " + warningMessage);
                if (warningMessage != null){
                     logger.fine("trying to send faces message to " + event.getComponent().getClientId());
                     FacesContext.getCurrentInstance().addMessage(event.getComponent().getClientId(), new FacesMessage(FacesMessage.SEVERITY_ERROR, "upload failure", warningMessage));
                }
            }
        }
    }

    
    
    public void handleFileUpload(FileUploadEvent event) {
        UploadedFile uFile = event.getFile();
        List<DataFile> dFileList = null;

        
        try {
            // Note: A single file may be unzipped into multiple files
            dFileList = ingestService.createDataFiles(workingVersion, uFile.getInputstream(), uFile.getFileName(), uFile.getContentType());
        } catch (IOException ioex) {
            logger.warning("Failed to process and/or save the file " + uFile.getFileName() + "; " + ioex.getMessage());
            return;
        }

        // -----------------------------------------------------------
        // Check if there are duplicate files or ingest warnings
        // -----------------------------------------------------------
        String warningMessage = processUploadedFileList(dFileList);
        if (warningMessage != null){
            logger.fine("trying to send faces message to " + event.getComponent().getClientId());
            FacesContext.getCurrentInstance().addMessage(event.getComponent().getClientId(), new FacesMessage(FacesMessage.SEVERITY_ERROR, "upload failure", warningMessage));
        }
    }

    /**
     *  After uploading via the site or Dropbox, 
     *  check the list of DataFile objects
     * @param dFileList 
     */
    private String processUploadedFileList(List<DataFile> dFileList){

        DataFile dFile = null;
        String duplicateFileNames = null;
        boolean multipleFiles = dFileList.size() > 1;
        boolean multipleDupes = false;
        String warningMessage = null;

        // -----------------------------------------------------------
        // Iterate through list of DataFile objects
        // -----------------------------------------------------------
        if (dFileList != null) {
            for (int i = 0; i < dFileList.size(); i++) {
                dFile = dFileList.get(i);

                //logger.info("dFile: " + dFile);
                
                // -----------------------------------------------------------
                // Check for ingest warnings
                // -----------------------------------------------------------
                if (dFile.isIngestProblem()) {
                    if (dFile.getIngestReportMessage() != null) {
                        if (warningMessage == null) {
                            warningMessage = dFile.getIngestReportMessage();
                        } else {
                            warningMessage = warningMessage.concat("; " + dFile.getIngestReportMessage());
                        }
                    }
                    dFile.setIngestDone();
                }

                // -----------------------------------------------------------
                // Check for duplicates -- e.g. file is already in the dataset
                // -----------------------------------------------------------
                if (!isDuplicate(dFile.getFileMetadata())) {
                    newFiles.add(dFile);        // looks good
                } else {
                    if (duplicateFileNames == null) {
                        duplicateFileNames = dFile.getFileMetadata().getLabel();
                    } else {
                        duplicateFileNames = duplicateFileNames.concat(", " + dFile.getFileMetadata().getLabel());
                        multipleDupes = true;
                    }

                    // remove the file from the dataset (since createDataFiles has already linked
                    // it to the dataset!
                    // first, through the filemetadata list, then through tht datafiles list:
                    Iterator<FileMetadata> fmIt = dataset.getEditVersion().getFileMetadatas().iterator();
                    while (fmIt.hasNext()) {
                        FileMetadata fm = fmIt.next();
                        if (fm.getId() == null && dFile.getStorageIdentifier().equals(fm.getDataFile().getStorageIdentifier())) {
                            fmIt.remove();
                            break;
                        }
                    }

                    Iterator<DataFile> dfIt = dataset.getFiles().iterator();
                    while (dfIt.hasNext()) {
                        DataFile dfn = dfIt.next();
                        if (dfn.getId() == null && dFile.getStorageIdentifier().equals(dfn.getStorageIdentifier())) {
                            dfIt.remove();
                            break;
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------
        // Formate error message for duplicate files
        // -----------------------------------------------------------
        if (duplicateFileNames != null) {
            String duplicateFilesErrorMessage = null;
            if (multipleDupes) {
                duplicateFilesErrorMessage = "The following files already exist in the dataset: " + duplicateFileNames;
            } else {
                if (multipleFiles) {
                    duplicateFilesErrorMessage = "The following file already exists in the dataset: " + duplicateFileNames;
                } else {
                    duplicateFilesErrorMessage = "This file already exists in this dataset. Please upload another file.";
                }
            }
            if (warningMessage == null) {
                warningMessage = duplicateFilesErrorMessage;
            } else {
                warningMessage = warningMessage.concat("; " + duplicateFilesErrorMessage);
            }
        }
        
        if (warningMessage != null) {
            logger.severe(warningMessage);
            return warningMessage;     // there's an issue return error message
        } else {
            return null;    // looks good, return null
        }
    }
    

    public boolean isLocked() {
        if (dataset != null) {
            logger.fine("checking lock status of dataset " + dataset.getId());
            if (dataset.isLocked()) {
                // refresh the dataset and version, if the current working
                // version of the dataset is locked:
            }
            Dataset lookedupDataset = datasetService.find(dataset.getId());
            DatasetLock datasetLock = null;
            if (lookedupDataset != null) {
                datasetLock = lookedupDataset.getDatasetLock();
                if (datasetLock != null) {
                    logger.fine("locked!");
                    return true;
                }
            }
        }
        return false;
    }

    
    // Methods for edit functions that are performed on one file at a time, 
    // in popups that block the rest of the page:
    
    private FileMetadata fileMetadataSelected = null;

    public void  setFileMetadataSelected(FileMetadata fm){
       setFileMetadataSelected(fm, null); 
    }
    
    public void setFileMetadataSelected(FileMetadata fm, String guestbook) {

        fileMetadataSelected = fm;
        logger.fine("set the file for the advanced options popup (" + fileMetadataSelected.getLabel() + ")");
    }

    public FileMetadata getFileMetadataSelected() {
        if (fileMetadataSelected != null) {
            logger.fine("returning file metadata for the advanced options popup (" + fileMetadataSelected.getLabel() + ")");
        } else {
            logger.fine("file metadata for the advanced options popup is null.");
        }
        return fileMetadataSelected;
    }

    public void clearFileMetadataSelected() {
        fileMetadataSelected = null;
    }
    
    public boolean isDesignatedDatasetThumbnail (FileMetadata fileMetadata) {
        if (fileMetadata != null) {
            if (fileMetadata.getDataFile() != null) {
                if (fileMetadata.getDataFile().getId() != null) {
                    if (fileMetadata.getDataFile().getOwner() != null) {
                        if (fileMetadata.getDataFile().equals(fileMetadata.getDataFile().getOwner().getThumbnailFile())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    
    /* 
     * Items for the "Designated this image as the Dataset thumbnail: 
     */
    
    private FileMetadata fileMetadataSelectedForThumbnailPopup = null; 

    public void  setFileMetadataSelectedForThumbnailPopup(FileMetadata fm){
       fileMetadataSelectedForThumbnailPopup = fm; 
       alreadyDesignatedAsDatasetThumbnail = getUseAsDatasetThumbnail();

    }
    
    public FileMetadata getFileMetadataSelectedForThumbnailPopup() {
        return fileMetadataSelectedForThumbnailPopup;
    }
    
    public void clearFileMetadataSelectedForThumbnailPopup() {
        fileMetadataSelectedForThumbnailPopup = null;
    }
    
    private boolean alreadyDesignatedAsDatasetThumbnail = false; 
    
    public boolean getUseAsDatasetThumbnail() {

        if (fileMetadataSelectedForThumbnailPopup != null) {
            if (fileMetadataSelectedForThumbnailPopup.getDataFile() != null) {
                if (fileMetadataSelectedForThumbnailPopup.getDataFile().getId() != null) {
                    if (fileMetadataSelectedForThumbnailPopup.getDataFile().getOwner() != null) {
                        if (fileMetadataSelectedForThumbnailPopup.getDataFile().equals(fileMetadataSelectedForThumbnailPopup.getDataFile().getOwner().getThumbnailFile())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    
    
    public void setUseAsDatasetThumbnail(boolean useAsThumbnail) {
        if (fileMetadataSelectedForThumbnailPopup != null) {
            if (fileMetadataSelectedForThumbnailPopup.getDataFile() != null) {
                if (fileMetadataSelectedForThumbnailPopup.getDataFile().getId() != null) { // ?
                    if (fileMetadataSelectedForThumbnailPopup.getDataFile().getOwner() != null) {
                        if (useAsThumbnail) {
                            fileMetadataSelectedForThumbnailPopup.getDataFile().getOwner().setThumbnailFile(fileMetadataSelectedForThumbnailPopup.getDataFile());
                        } else if (getUseAsDatasetThumbnail()) {
                            fileMetadataSelectedForThumbnailPopup.getDataFile().getOwner().setThumbnailFile(null);
                        }
                    }
                }
            }
        }
    }

    public void saveAsDesignatedThumbnail() {
        // We don't need to do anything specific to save this setting, because
        // the setUseAsDatasetThumbnail() method, above, has already updated the
        // file object appropriately. 
        // However, once the "save" button is pressed, we want to show a success message, if this is 
        // a new image has been designated as such:
        if (getUseAsDatasetThumbnail() && !alreadyDesignatedAsDatasetThumbnail) {
            String successMessage = JH.localize("file.assignedDataverseImage.success");
            logger.info(successMessage);
            successMessage = successMessage.replace("{0}", fileMetadataSelectedForThumbnailPopup.getLabel());
            JsfHelper.addFlashMessage(successMessage);
        }
        
        // And reset the selected fileMetadata:
        
        fileMetadataSelectedForThumbnailPopup = null;
    }
    
    /* 
     * Items for the "Tags (Categories)" popup.
     *
     */
    private FileMetadata fileMetadataSelectedForTagsPopup = null; 

    public void  setFileMetadataSelectedForTagsPopup(FileMetadata fm){
       fileMetadataSelectedForTagsPopup = fm; 
    }
    
    public FileMetadata getFileMetadataSelectedForTagsPopup() {
        return fileMetadataSelectedForTagsPopup;
    }
    
    public void clearFileMetadataSelectedForTagsPopup() {
        fileMetadataSelectedForTagsPopup = null;
    }
    
    /*
     * 1. Tabular File Tags: 
     */
    
    private List<String> tabFileTags = null;

    public List<String> getTabFileTags() {
        if (tabFileTags == null) {
            tabFileTags = DataFileTag.listTags();
        }
        return tabFileTags;
    }

    public void setTabFileTags(List<String> tabFileTags) {
        this.tabFileTags = tabFileTags;
    }

    private String[] selectedTags = {};

    public String[] getSelectedTags() {

        selectedTags = null;
        selectedTags = new String[0];

        if (fileMetadataSelectedForTagsPopup != null) {
            if (fileMetadataSelectedForTagsPopup.getDataFile() != null
                    && fileMetadataSelectedForTagsPopup.getDataFile().getTags() != null
                    && fileMetadataSelectedForTagsPopup.getDataFile().getTags().size() > 0) {

                selectedTags = new String[fileMetadataSelectedForTagsPopup.getDataFile().getTags().size()];

                for (int i = 0; i < fileMetadataSelectedForTagsPopup.getDataFile().getTags().size(); i++) {
                    selectedTags[i] = fileMetadataSelectedForTagsPopup.getDataFile().getTags().get(i).getTypeLabel();
                }
            }
        }
        return selectedTags;
    }

    public void setSelectedTags(String[] selectedTags) {
        this.selectedTags = selectedTags;
    }

    /*
     * "File Tags" (aka "File Categories"): 
    */
    
    private String newCategoryName = null;

    public String getNewCategoryName() {
        return newCategoryName;
    }

    public void setNewCategoryName(String newCategoryName) {
        this.newCategoryName = newCategoryName;
    }

    /* This method handles saving both "tabular file tags" and 
     * "file categories" (which are also considered "tags" in 4.0)
    */
    public void saveFileTagsAndCategories() {
        // 1. File categories:
        // we don't need to do anything for the file categories that the user
        // selected from the pull down list; that was done directly from the 
        // page with the FileMetadata.setCategoriesByName() method. 
        // So here we only need to take care of the new, custom category
        // name, if entered: 
        
        logger.fine("New category name: " + newCategoryName);

        if (fileMetadataSelectedForTagsPopup != null && newCategoryName != null) {
            logger.fine("Adding new category, for file " + fileMetadataSelectedForTagsPopup.getLabel());
            fileMetadataSelectedForTagsPopup.addCategoryByName(newCategoryName);
        } else {
            logger.fine("No FileMetadata selected, or no category specified!");
        }
        newCategoryName = null;
        
        // 2. Tabular DataFile Tags: 

        if (selectedTags != null) {
        
            if (fileMetadataSelectedForTagsPopup != null && fileMetadataSelectedForTagsPopup.getDataFile() != null) {
                fileMetadataSelectedForTagsPopup.getDataFile().setTags(null);
                for (int i = 0; i < selectedTags.length; i++) {
                    
                    DataFileTag tag = new DataFileTag();
                    try {
                        tag.setTypeByLabel(selectedTags[i]);
                        tag.setDataFile(fileMetadataSelectedForTagsPopup.getDataFile());
                        fileMetadataSelectedForTagsPopup.getDataFile().addTag(tag);
                        
                    } catch (IllegalArgumentException iax) {
                        // ignore 
                    }
                }
                
                // success message: 
                String successMessage = JH.localize("file.assignedTabFileTags.success");
                logger.fine(successMessage);
                successMessage = successMessage.replace("{0}", fileMetadataSelectedForTagsPopup.getLabel());
                JsfHelper.addFlashMessage(successMessage);
            }
            // reset:
            selectedTags = null;
        }
        
        fileMetadataSelectedForTagsPopup = null;

    }
    
    
    /* 
     * Items for the "Advanced (Ingest) Options" popup. 
     * 
     */
    private FileMetadata fileMetadataSelectedForIngestOptionsPopup = null; 

    public void  setFileMetadataSelectedForIngestOptionsPopup(FileMetadata fm){
       fileMetadataSelectedForIngestOptionsPopup = fm; 
    }
    
    public FileMetadata getFileMetadataSelectedForIngestOptionsPopup() {
        return fileMetadataSelectedForIngestOptionsPopup;
    }
    
    public void clearFileMetadataSelectedForIngestOptionsPopup() {
        fileMetadataSelectedForIngestOptionsPopup = null;
    }
    
    private String ingestLanguageEncoding = null;

    public String getIngestLanguageEncoding() {
        if (ingestLanguageEncoding == null) {
            return "UTF8 (default)";
        }
        return ingestLanguageEncoding;
    }

    public void setIngestLanguageEncoding(String ingestLanguageEncoding) {
        this.ingestLanguageEncoding = ingestLanguageEncoding;
    }

    public void setIngestEncoding(String ingestEncoding) {
        ingestLanguageEncoding = ingestEncoding;
    }

    private String savedLabelsTempFile = null;

    public void handleLabelsFileUpload(FileUploadEvent event) {
        logger.fine("entering handleUpload method.");
        UploadedFile file = event.getFile();

        if (file != null) {

            InputStream uploadStream = null;
            try {
                uploadStream = file.getInputstream();
            } catch (IOException ioex) {
                logger.warning("the file " + file.getFileName() + " failed to upload!");
                FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_WARN, "upload failure", "the file " + file.getFileName() + " failed to upload!");
                FacesContext.getCurrentInstance().addMessage(null, message);
                return;
            }

            savedLabelsTempFile = saveTempFile(uploadStream);

            logger.fine(file.getFileName() + " is successfully uploaded.");
            FacesMessage message = new FacesMessage("Succesful", file.getFileName() + " is uploaded.");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }

        // process file (i.e., just save it in a temp location; for now):
    }

    private String saveTempFile(InputStream input) {
        if (input == null) {
            return null;
        }
        byte[] buffer = new byte[8192];
        int bytesRead = 0;
        File labelsFile = null;
        FileOutputStream output = null;
        try {
            labelsFile = File.createTempFile("tempIngestLabels.", ".txt");
            output = new FileOutputStream(labelsFile);
            while ((bytesRead = input.read(buffer)) > -1) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException ioex) {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                }
            }
            return null;
        }

        if (labelsFile != null) {
            return labelsFile.getAbsolutePath();
        }
        return null;
    }

    public void saveAdvancedOptions() {
        
        // Language encoding for SPSS SAV (and, possibly, other tabular ingests:) 
        if (ingestLanguageEncoding != null) {
            if (fileMetadataSelectedForIngestOptionsPopup != null && fileMetadataSelectedForIngestOptionsPopup.getDataFile() != null) {
                if (fileMetadataSelectedForIngestOptionsPopup.getDataFile().getIngestRequest() == null) {
                    IngestRequest ingestRequest = new IngestRequest();
                    ingestRequest.setDataFile(fileMetadataSelectedForIngestOptionsPopup.getDataFile());
                    fileMetadataSelectedForIngestOptionsPopup.getDataFile().setIngestRequest(ingestRequest);

                }
                fileMetadataSelectedForIngestOptionsPopup.getDataFile().getIngestRequest().setTextEncoding(ingestLanguageEncoding);
            }
        }
        ingestLanguageEncoding = null;

        // Extra labels for SPSS POR (and, possibly, other tabular ingests:)
        // (we are adding this parameter to the IngestRequest now, instead of back
        // when it was uploaded. This is because we want the user to be able to 
        // hit cancel and bail out, until they actually click 'save' in the 
        // "advanced options" popup) -- L.A. 4.0 beta 11
        if (savedLabelsTempFile != null) {
            if (fileMetadataSelectedForIngestOptionsPopup != null && fileMetadataSelectedForIngestOptionsPopup.getDataFile() != null) {
                if (fileMetadataSelectedForIngestOptionsPopup.getDataFile().getIngestRequest() == null) {
                    IngestRequest ingestRequest = new IngestRequest();
                    ingestRequest.setDataFile(fileMetadataSelectedForIngestOptionsPopup.getDataFile());
                    fileMetadataSelectedForIngestOptionsPopup.getDataFile().setIngestRequest(ingestRequest);
                }
                fileMetadataSelectedForIngestOptionsPopup.getDataFile().getIngestRequest().setLabelsFile(savedLabelsTempFile);
            }
        }
        savedLabelsTempFile = null;

        fileMetadataSelectedForIngestOptionsPopup = null;
    }

    public String getFileDateToDisplay(FileMetadata fileMetadata) {
        Date fileDate = null;
        DataFile datafile = fileMetadata.getDataFile();
        if (datafile != null) {
            boolean fileHasBeenReleased = datafile.isReleased();
            if (fileHasBeenReleased) {
                Timestamp filePublicationTimestamp = datafile.getPublicationDate();
                if (filePublicationTimestamp != null) {
                    fileDate = filePublicationTimestamp;
                }
            } else {
                Timestamp fileCreateTimestamp = datafile.getCreateDate();
                if (fileCreateTimestamp != null) {
                    fileDate = fileCreateTimestamp;
                }
            }
        }
        if (fileDate != null) {
            return displayDateFormat.format(fileDate);
        }

        return "";
    }

    private void populateFileMetadatas() {
        fileMetadatas = new ArrayList<>();

        Long datasetVersionId = workingVersion.getId();
        if (datasetVersionId != null) {
            if (selectedFileIdsList != null) {
                for (Long fileId : selectedFileIdsList) {
                    logger.info("attempting to retrieve file metadata for version id "+datasetVersionId+" and file id "+fileId);
                    FileMetadata fileMetadata = datafileService.findFileMetadataByFileAndVersionId(fileId, datasetVersionId);
                    if (fileMetadata != null) {
                        logger.info("Success!");
                        fileMetadatas.add(fileMetadata);
                    } else {
                        logger.info("Failed to find file metadata.");
                    }
                }
            }
        } else {
            logger.info("Null version id in the populateFileMetadatas()!");
        }
    }

   

}