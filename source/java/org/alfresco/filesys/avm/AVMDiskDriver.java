/*
 * Copyright (C) 2006 Alfresco, Inc.
 *
 * Licensed under the Mozilla Public License version 1.1 
 * with a permitted attribution clause. You may obtain a
 * copy of the License at
 *
 *   http://www.alfresco.org/legal/license.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */

package org.alfresco.filesys.avm;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.SortedMap;

import javax.transaction.UserTransaction;

import org.alfresco.config.ConfigElement;
import org.alfresco.filesys.server.SrvSession;
import org.alfresco.filesys.server.core.DeviceContext;
import org.alfresco.filesys.server.core.DeviceContextException;
import org.alfresco.filesys.server.filesys.AccessDeniedException;
import org.alfresco.filesys.server.filesys.DirectoryNotEmptyException;
import org.alfresco.filesys.server.filesys.DiskInterface;
import org.alfresco.filesys.server.filesys.FileAttribute;
import org.alfresco.filesys.server.filesys.FileExistsException;
import org.alfresco.filesys.server.filesys.FileInfo;
import org.alfresco.filesys.server.filesys.FileName;
import org.alfresco.filesys.server.filesys.FileOpenParams;
import org.alfresco.filesys.server.filesys.FileStatus;
import org.alfresco.filesys.server.filesys.FileSystem;
import org.alfresco.filesys.server.filesys.NetworkFile;
import org.alfresco.filesys.server.filesys.SearchContext;
import org.alfresco.filesys.server.filesys.SrvDiskInfo;
import org.alfresco.filesys.server.filesys.TreeConnection;
import org.alfresco.filesys.util.WildCard;
import org.alfresco.repo.security.authentication.AuthenticationComponent;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.avm.AVMExistsException;
import org.alfresco.service.cmr.avm.AVMNodeDescriptor;
import org.alfresco.service.cmr.avm.AVMNotFoundException;
import org.alfresco.service.cmr.avm.AVMService;
import org.alfresco.service.cmr.avm.AVMWrongTypeException;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.alfresco.service.transaction.TransactionService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * AVM Repository Filesystem Driver Class
 * 
 * <p>Provides a filesystem interface for various protocols such as SMB/CIFS and FTP.
 * 
 * @author GKSpencer
 */
public class AVMDiskDriver implements DiskInterface {

    // Logging
    
    private static final Log logger = LogFactory.getLog(AVMDiskDriver.class);
    
    // Configuration key names
    
    private static final String KEY_STORE 		= "storePath";
    private static final String KEY_VERSION		= "version";
    private static final String KEY_CREATE		= "createStore";

    // AVM path seperator
    
    public static final char AVM_SEPERATOR			= '/';
    public static final String AVM_SEPERATOR_STR	= "/";
    
    // Services and helpers
    
    private AVMService m_avmService;
    private TransactionService m_transactionService;
    private MimetypeService m_mimetypeService;
    
    private AuthenticationComponent m_authComponent;
    private AuthenticationService m_authService;
    
    // Service registry for desktop actions
    
    private ServiceRegistry m_serviceRegistry;

    /**
     * Default constructor
     */
    public AVMDiskDriver()
    {
    }
    
    /**
     * Return the AVM service
     * 
     * @return AVMService
     */
    public final AVMService getAvmService()
    {
    	return m_avmService;
    }
    
    /**
     * Return the authentication service
     * 
     * @return AuthenticationService
     */
    public final AuthenticationService getAuthenticationService()
    {
    	return m_authService;
    }
    
    /**
     * Return the transaction service
     * 
     * @return TransactionService
     */
    public final TransactionService getTransactionService()
    {
    	return m_transactionService;
    }
    
    /**
     * Return the service registry
     * 
     * @return ServiceRegistry
     */
    public final ServiceRegistry getServiceRegistry()
    {
    	return m_serviceRegistry;
    }

    /**
     * Set the AVM service
     * 
     * @param avmService AVMService
     */
    public void setAvmService(AVMService avmService)
    {
    	m_avmService = avmService;
    }
    
    /**
     * Set the transaction service
     * 
     * @param transactionService the transaction service
     */
    public void setTransactionService(TransactionService transactionService)
    {
        m_transactionService = transactionService;
    }

    /**
     * Set the service registry
     * 
     * @param serviceRegistry
     */
    public void setServiceRegistry(ServiceRegistry serviceRegistry)
    {
    	m_serviceRegistry = serviceRegistry;
    }
    
    /**
     * Set the authentication component
     * 
     * @param authComponent AuthenticationComponent
     */
    public void setAuthenticationComponent(AuthenticationComponent authComponent)
    {
        m_authComponent = authComponent;
    }

    /**
     * Set the authentication service
     * 
     * @param authService AuthenticationService
     */
    public void setAuthenticationService(AuthenticationService authService)
    {
    	m_authService = authService;
    }
    
    /**
     * Set the mimetype service
     * 
     * @param mimetypeService MimetypeService
     */
    public void setMimetypeService(MimetypeService mimetypeService)
    {
        m_mimetypeService = mimetypeService;
    }
    
    /**
     * Parse and validate the parameter string and create a device context object for this instance
     * of the shared device.
     * 
     * @param cfg ConfigElement
     * @return DeviceContext
     * @exception DeviceContextException
     */
    public DeviceContext createContext(ConfigElement cfg)
    	throws DeviceContextException
    {
        // Use the system user as the authenticated context for the filesystem initialization
        
        m_authComponent.setCurrentUser( m_authComponent.getSystemUserName());
        
        // Wrap the initialization in a transaction
        
        UserTransaction tx = m_transactionService.getUserTransaction(false);

        AVMContext context = null;
        
        try
        {
            // Start the transaction
            
            if ( tx != null)
                tx.begin();
            
            // Get the store path
            
            ConfigElement storeElement = cfg.getChild(KEY_STORE);
            if (storeElement == null || storeElement.getValue() == null || storeElement.getValue().length() == 0)
                throw new DeviceContextException("Device missing init value: " + KEY_STORE);
            String storePath = storeElement.getValue();

            // Get the version if specified, or default to the head version
            
            int version = AVMContext.VERSION_HEAD;
            
            ConfigElement versionElem = cfg.getChild(KEY_VERSION);
            if ( versionElem != null)
            {
            	// Check if the version is valid
            	
            	if ( versionElem.getValue() == null || versionElem.getValue().length() == 0)
            		throw new DeviceContextException("Store version not specified");
            	
            	// Validate the version id
            	
            	try
            	{
            		version = Integer.parseInt( versionElem.getValue());
            	}
            	catch ( NumberFormatException ex)
            	{
            		throw new DeviceContextException("Invalid store version specified, " + versionElem.getValue());
            	}
            	
            	// Range check the version id
            	
            	if ( version < 0 && version != AVMContext.VERSION_HEAD)
            		throw new DeviceContextException("Invalid store version id specified, " + version);
            }
            
            // Check if the create flag is enabled
            
            ConfigElement createStore = cfg.getChild( KEY_CREATE);
            
            // Validate the store path
            
            AVMNodeDescriptor rootNode = m_avmService.lookup( version, storePath);
            if ( rootNode == null)
            {
            	// Check if the store should be created
            	
            	if ( createStore == null || version != AVMContext.VERSION_HEAD)
            		throw new DeviceContextException("Invalid store path/version, " + storePath + " (" + version + ")");
            	
            	// Parse the store path
            	
            	String storeName = null;
            	String path = null;
            	
            	int pos = storePath.indexOf(":/");
            	if ( pos != -1)
            	{
            		storeName = storePath.substring(0, pos);
            		if ( storePath.length() > pos)
            			path = storePath.substring(pos + 2);
            	}
            	else
            		storeName = storePath;
            	
            	// Create a new store, and the path if specified
            	
            	m_avmService.createAVMStore( storeName);
            	if ( path != null)
            	{
            		// TODO:
            	}
            	
            	// Validate the store path again
            	
            	rootNode = m_avmService.lookup( version, storePath);
            	if ( rootNode == null)
            		throw new DeviceContextException("Failed to create new store " + storePath);
            }

            // Commit the transaction
            
            tx.commit();
            tx = null;
            
            // Create the context
            
            context = new AVMContext(storePath, version);

            // Default the filesystem to look like an 80Gb sized disk with 90% free space
            
            context.setDiskInformation(new SrvDiskInfo(2560000, 64, 512, 2304000));
            
            // Set parameters
            
            context.setFilesystemAttributes(FileSystem.CasePreservedNames + FileSystem.UnicodeOnDisk +
            		FileSystem.CaseSensitiveSearch);
        }
        catch (Exception ex)
        {
            logger.error("Error during create context", ex);
            
            // Rethrow the exception
            
            throw new DeviceContextException("Driver setup error, " + ex.getMessage());
        }
        finally
        {
            // If there is an active transaction then roll it back
            
            if ( tx != null)
            {
                try
                {
                    tx.rollback();
                }
                catch (Exception ex)
                {
                    logger.warn("Failed to rollback transaction", ex);
                }
            }
        }

        // Return the context for this shared filesystem
        
        return context;
    }

    /**
     * Build the full store path for a file/folder using the share relative path
     * 
     * @param ctx AVMContext
     * @param path String
     * @return String
     */
    protected final String buildStorePath( AVMContext ctx, String path)
    {
    	// Build the store path
    	
    	StringBuilder storePath = new StringBuilder();
    	
    	storePath.append( ctx.getStorePath());
    	if ( path == null || path.length() == 0)
    	{
    		storePath.append( AVM_SEPERATOR);
    	}
    	else
    	{
	    	if ( path.startsWith( FileName.DOS_SEPERATOR_STR) == false)
	    		storePath.append( AVM_SEPERATOR);
	    	
	    	storePath.append( path.replace( FileName.DOS_SEPERATOR, AVM_SEPERATOR));
    	}
    	
    	return storePath.toString();
    }
    
	/**
     * Close the file.
     * 
     * @param sess Server session
     * @param tree Tree connection.
     * @param file Network file context.
     * @exception java.io.IOException If an error occurs.
     */
    public void closeFile(SrvSession sess, TreeConnection tree, NetworkFile file)
    	throws java.io.IOException
    {
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    		logger.debug("Close file " + file.getFullName());
    	
        //	Close the file
        
        file.closeFile();

        //	Check if the file/directory is marked for delete
      	
      	if ( file.hasDeleteOnClose()) {
      		
      		//	Check for a file or directory
      		
      		if ( file.isDirectory())
      			deleteDirectory(sess, tree, file.getFullName());
      		else
      			deleteFile(sess, tree, file.getFullName());
      	}
    	
    }

    /**
     * Create a new directory on this file system.
     * 
     * @param sess Server session
     * @param tree Tree connection.
     * @param params Directory create parameters
     * @exception java.io.IOException If an error occurs.
     */
    public void createDirectory(SrvSession sess, TreeConnection tree, FileOpenParams params)
    	throws java.io.IOException
    {
    	// Check if the filesystem is writable
    	
    	AVMContext ctx = (AVMContext) tree.getContext();
    	if ( ctx.isVersion() != AVMContext.VERSION_HEAD)
    		throw new AccessDeniedException("Cannot create " + params.getPath() + ", filesys not writable");
    	
    	// Split the path to get the new folder name and relative path
    	
    	String[] paths = FileName.splitPath( params.getPath());
    	
    	// Convert the relative path to a store path
    	
    	String storePath = buildStorePath( ctx, paths[0]);
    	
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    		logger.debug("Create directory params=" + params + ", storePath=" + storePath + ", name=" + paths[1]);
    	
    	// Create a new file
    	
    	sess.beginTransaction( m_transactionService, false);
    	
    	try
    	{
    		// Create the new file entry

    		m_avmService.createDirectory( storePath, paths[1]);
    	}
    	catch ( AVMExistsException ex)
    	{
    		throw new FileExistsException( params.getPath());
    	}
    	catch ( AVMNotFoundException ex)
    	{
    		throw new FileNotFoundException( params.getPath());
    	}
    	catch ( AVMWrongTypeException ex)
    	{
    		throw new FileNotFoundException( params.getPath());
    	}
    }
    
    /**
     * Create a new file on the file system.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param params File create parameters
     * @return NetworkFile
     * @exception java.io.IOException If an error occurs.
     */
    public NetworkFile createFile(SrvSession sess, TreeConnection tree, FileOpenParams params)
    	throws java.io.IOException
    {
    	// Check if the filesystem is writable
    	
    	AVMContext ctx = (AVMContext) tree.getContext();
    	if ( ctx.isVersion() != AVMContext.VERSION_HEAD)
    		throw new AccessDeniedException("Cannot create " + params.getPath() + ", filesys not writable");
    	
    	// Split the path to get the file name and relative path
    	
    	String[] paths = FileName.splitPath( params.getPath());
    	
    	// Convert the relative path to a store path
    	
    	String storePath = buildStorePath( ctx, paths[0]);
    	
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    		logger.debug("Create file params=" + params + ", storePath=" + storePath + ", name=" + paths[1]);
    	
    	// Create a new file
    	
    	sess.beginTransaction( m_transactionService, false);
    	
    	AVMNetworkFile netFile = null;
    	
    	try
    	{
    		// Create the new file entry

    		m_avmService.createFile( storePath, paths[1]).close();

    		// Get the new file details
    		
    		String fileStorePath = buildStorePath( ctx, params.getPath());
    		AVMNodeDescriptor nodeDesc = m_avmService.lookup( ctx.isVersion(), fileStorePath);

    		if ( nodeDesc != null)
	    	{
	    	    //	Create the network file object for the new file
	    	    
	    	    netFile = new AVMNetworkFile( nodeDesc, fileStorePath, ctx.isVersion(), m_avmService);
    	    	netFile.setGrantedAccess(NetworkFile.READWRITE);
	    	    netFile.setFullName(params.getPath());
	    	    
	    	    // Set the mime-type for the new file
	    	    
	    	    netFile.setMimeType( m_mimetypeService.guessMimetype( paths[1]));
	    	}
    	}
    	catch ( AVMExistsException ex)
    	{
    		throw new FileExistsException( params.getPath());
    	}
    	catch ( AVMNotFoundException ex)
    	{
    		throw new FileNotFoundException( params.getPath());
    	}
    	catch ( AVMWrongTypeException ex)
    	{
    		throw new FileNotFoundException( params.getPath());
    	}
    	
    	// Return the file
    	
    	return netFile;
    }
    
    /**
     * Delete the directory from the filesystem.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param dir Directory name.
     * @exception java.io.IOException The exception description.
     */
    public void deleteDirectory(SrvSession sess, TreeConnection tree, String dir)
    	throws java.io.IOException
    {
    	// Convert the relative path to a store path
    	
    	AVMContext ctx = (AVMContext) tree.getContext();
    	String storePath = buildStorePath( ctx, dir);
    	
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    		logger.debug("Delete directory, path=" + dir + ", storePath=" + storePath);
    	
    	// Make sure the path is to a folder before deleting it
    	
    	sess.beginTransaction( m_transactionService, false);
    	
    	try
    	{
    		AVMNodeDescriptor nodeDesc = m_avmService.lookup( ctx.isVersion(), storePath);
	    	if ( nodeDesc != null)
	    	{
	    		// Check that we are deleting a folder
	    		
	    		if ( nodeDesc.isDirectory())
	    		{
	    			// Make sure the directory is empty
	    			
	    			SortedMap<String, AVMNodeDescriptor> fileList = m_avmService.getDirectoryListing( nodeDesc);
	    			if ( fileList != null && fileList.size() > 0)
	    				throw new DirectoryNotEmptyException( dir);
	    			
	    			// Delete the folder
	    			
	    			m_avmService.removeNode( storePath);
	    		}
	    		else
	    			throw new IOException( "Delete directory path is not a directory, " + dir);
	    	}
    	}
    	catch ( AVMNotFoundException ex)
    	{
    		throw new IOException( "Directory not found, " + dir);
    	}
    	catch ( AVMWrongTypeException ex)
    	{
    		throw new IOException( "Invalid path, " + dir);
    	}
    }

    /**
     * Delete the specified file.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param file NetworkFile
     * @exception java.io.IOException The exception description.
     */
    public void deleteFile(SrvSession sess, TreeConnection tree, String name)
    	throws java.io.IOException
    {
    	// Convert the relative path to a store path
    	
    	AVMContext ctx = (AVMContext) tree.getContext();
    	String storePath = buildStorePath( ctx, name);
    	
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    		logger.debug("Delete file, path=" + name + ", storePath=" + storePath);
    	
    	// Make sure the path is to a file before deleting it
    	
    	sess.beginTransaction( m_transactionService, false);
    	
    	try
    	{
    		AVMNodeDescriptor nodeDesc = m_avmService.lookup( ctx.isVersion(), storePath);
	    	if ( nodeDesc != null)
	    	{
	    		// Check that we are deleting a file
	    		
	    		if ( nodeDesc.isFile())
	    		{
	    			// Delete the file
	    			
	    			m_avmService.removeNode( storePath);
	    		}
	    		else
	    			throw new IOException( "Delete file path is not a file, " + name);
	    	}
    	}
    	catch ( AVMNotFoundException ex)
    	{
    		throw new IOException( "File not found, " + name);
    	}
    	catch ( AVMWrongTypeException ex)
    	{
    		throw new IOException( "Invalid path, " + name);
    	}
    }

    /**
     * Check if the specified file exists, and whether it is a file or directory.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param name java.lang.String
     * @return int
     * @see FileStatus
     */
    public int fileExists(SrvSession sess, TreeConnection tree, String name)
    {
    	// Convert the relative path to a store path
    	
    	AVMContext ctx = (AVMContext) tree.getContext();
    	String storePath = buildStorePath( ctx, name);
    	
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    		logger.debug("File exists check, path=" + name + ", storePath=" + storePath);
    	
    	// Search for the file/folder
    	
    	sess.beginTransaction( m_transactionService, true);
    	
    	int status = FileStatus.NotExist;
    	AVMNodeDescriptor nodeDesc = m_avmService.lookup( ctx.isVersion(), storePath);
    	
    	if ( nodeDesc != null)
    	{
    		// Check if the path is to a file or folder
    		
    		if ( nodeDesc.isDirectory())
    			status = FileStatus.DirectoryExists;
    		else
    			status = FileStatus.FileExists;
    	}
    	
    	// Return the file status
    	
    	return status;
    }

    /**
     * Flush any buffered output for the specified file.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param file Network file context.
     * @exception java.io.IOException The exception description.
     */
    public void flushFile(SrvSession sess, TreeConnection tree, NetworkFile file)
    	throws java.io.IOException
    {
        //	Flush the file
        
        file.flushFile();
    }

    /**
     * Get the file information for the specified file.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param name File name/path that information is required for.
     * @return File information if valid, else null
     * @exception java.io.IOException The exception description.
     */
    public FileInfo getFileInformation(SrvSession sess, TreeConnection tree, String name)
    	throws java.io.IOException
    {
    	// Convert the relative path to a store path
    	
    	AVMContext ctx = (AVMContext) tree.getContext();
    	String storePath = buildStorePath( ctx, name);
    	
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    		logger.debug("Get file information, path=" + name + ", storePath=" + storePath);

    	// Search for the file/folder
    	
    	sess.beginTransaction( m_transactionService, true);
    	
    	FileInfo info = null;
    	AVMNodeDescriptor nodeDesc = m_avmService.lookup( ctx.isVersion(), storePath);
    	
    	if ( nodeDesc != null)
    	{
    		// Create, and fill in, the file information
    		
    		info = new FileInfo();
        	
        	info.setFileName( nodeDesc.getName());
        	
        	if ( nodeDesc.isFile())
        	{
        		info.setFileSize( nodeDesc.getLength());
        		info.setAllocationSize((nodeDesc.getLength() + 512L) & 0xFFFFFFFFFFFFFE00L);
        	}
        	else
        		info.setFileSize( 0L);

        	info.setAccessDateTime( nodeDesc.getAccessDate());
        	info.setCreationDateTime( nodeDesc.getCreateDate());
        	info.setModifyDateTime( nodeDesc.getModDate());

        	// Build the file attributes
        	
        	int attr = 0;
        	
        	if ( nodeDesc.isDirectory())
        		attr += FileAttribute.Directory;
        	
        	if ( nodeDesc.getName().startsWith( ".") ||
        			nodeDesc.getName().equalsIgnoreCase( "Desktop.ini") ||
        			nodeDesc.getName().equalsIgnoreCase( "Thumbs.db"))
        		attr += FileAttribute.Hidden;
        	
        	// Mark the file/folder as read-only if not the head version
        	
        	if ( ctx.isVersion() != AVMContext.VERSION_HEAD)
        		attr += FileAttribute.ReadOnly;
        	
        	info.setFileAttributes( attr);

        	// DEBUG
        	
        	if ( logger.isDebugEnabled())
        		logger.debug("  File info=" + info);
    	}
    	
    	// Return the file information
    	
    	return info;
    }

    /**
     * Determine if the disk device is read-only.
     * 
     * @param sess Server session
     * @param ctx Device context
     * @return boolean
     * @exception java.io.IOException If an error occurs.
     */
    public boolean isReadOnly(SrvSession sess, DeviceContext ctx)
    	throws java.io.IOException
    {
    	// Check if the version indicates the head version, only the head is writable
    	
    	AVMContext avmCtx = (AVMContext) ctx;
    	return avmCtx.isVersion() == AVMContext.VERSION_HEAD ? true : false;
    }

    /**
     * Open a file on the file system.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param params File open parameters
     * @return NetworkFile
     * @exception java.io.IOException If an error occurs.
     */
    public NetworkFile openFile(SrvSession sess, TreeConnection tree, FileOpenParams params)
    	throws java.io.IOException
    {
    	// Convert the relative path to a store path
    	
    	AVMContext ctx = (AVMContext) tree.getContext();
    	String storePath = buildStorePath( ctx, params.getPath());
    	
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    		logger.debug("Open file params=" + params + ", storePath=" + storePath);
    	
    	// Search for the file/folder
    	
    	sess.beginTransaction( m_transactionService, true);
    	
    	AVMNetworkFile netFile = null;
    	
    	try
    	{
    		// Get the details of the file/folder

    		AVMNodeDescriptor nodeDesc = m_avmService.lookup( ctx.isVersion(), storePath);
    	
	    	if ( nodeDesc != null)
	    	{
	    	    //	Check if the filesystem is read-only and write access has been requested
	    	    
	    	    if ( ctx.isVersion() != AVMContext.VERSION_HEAD && ( params.isReadWriteAccess() || params.isWriteOnlyAccess()))
	    	      throw new AccessDeniedException("File " + params.getPath() + " is read-only");
	    	    
	    	    //	Create the network file object for the opened file/folder
	    	    
	    	    netFile = new AVMNetworkFile( nodeDesc, storePath, ctx.isVersion(), m_avmService);
	    	    
	    	    if ( params.isReadOnlyAccess() || ctx.isVersion() != AVMContext.VERSION_HEAD)
	    	    	netFile.setGrantedAccess(NetworkFile.READONLY);
	    		else
	    	    	netFile.setGrantedAccess(NetworkFile.READWRITE);
	    	    	
	    	    netFile.setFullName(params.getPath());
	    	    
	    	    
	    	    // Set the mime-type for the new file
	    	    
	    	    netFile.setMimeType( m_mimetypeService.guessMimetype( params.getPath()));
	    	}
	    	else
	    		throw new FileNotFoundException( params.getPath());
    	}
    	catch ( AVMNotFoundException ex)
    	{
    		throw new FileNotFoundException( params.getPath());
    	}
    	catch ( AVMWrongTypeException ex)
    	{
    		throw new FileNotFoundException( params.getPath());
    	}
    	
    	// Return the file
    	
    	return netFile;
    }

    /**
     * Read a block of data from the specified file.
     * 
     * @param sess Session details
     * @param tree Tree connection
     * @param file Network file
     * @param buf Buffer to return data to
     * @param bufPos Starting position in the return buffer
     * @param siz Maximum size of data to return
     * @param filePos File offset to read data
     * @return Number of bytes read
     * @exception java.io.IOException The exception description.
     */
    public int readFile(SrvSession sess, TreeConnection tree, NetworkFile file, byte[] buf, int bufPos, int siz,
    					long filePos)
    	throws java.io.IOException
    {
  	  	// Check if the file is a directory
    	
		if ( file.isDirectory())
			throw new AccessDeniedException();
      
    	// If the content channel is not open for the file then start a transaction
    	
    	AVMNetworkFile avmFile = (AVMNetworkFile) file;
    	
    	if ( avmFile.hasContentChannel() == false)
    		sess.beginTransaction( m_transactionService, true);
    	
		// Read the file

		int rdlen = file.readFile(buf, siz, bufPos, filePos);

		// If we have reached end of file return a zero length read

		if (rdlen == -1)
			rdlen = 0;

		//  Return the actual read length

		return rdlen;
    }

    /**
     * Rename the specified file.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param oldName java.lang.String
     * @param newName java.lang.String
     * @exception java.io.IOException The exception description.
     */
    public void renameFile(SrvSession sess, TreeConnection tree, String oldName, String newName)
    	throws java.io.IOException
    {
    	// Split the relative paths into parent and file/folder name pairs
    	
    	AVMContext ctx = (AVMContext) tree.getContext();
    	
    	String[] oldPaths = FileName.splitPath( oldName);
    	String[] newPaths = FileName.splitPath( newName);
    	
    	// Convert the parent paths to store paths
    	
    	oldPaths[0] = buildStorePath( ctx, oldPaths[0]);
    	newPaths[0] = buildStorePath( ctx, newPaths[0]);
    	
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    	{
    		logger.debug("Rename from path=" + oldPaths[0] + ", name=" + oldPaths[1]);
    		logger.debug("        new path=" + newPaths[0] + ", name=" + newPaths[1]);
    	}

    	// Start a transaction for the rename
    	
    	sess.beginTransaction( m_transactionService, false);
    	
    	try
    	{
    		// Rename the file/folder
    		
    		m_avmService.rename( oldPaths[0], oldPaths[1], newPaths[0], newPaths[1]);
    	}
    	catch ( AVMNotFoundException ex)
    	{
    		throw new IOException( "Source not found, " + oldName);
    	}
    	catch ( AVMWrongTypeException ex)
    	{
    		throw new IOException( "Invalid path, " + oldName);
    	}
    	catch ( AVMExistsException ex)
    	{
    		throw new FileExistsException( "Destination exists, " + newName);
    	}
    }

    /**
     * Seek to the specified file position.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param file Network file.
     * @param pos Position to seek to.
     * @param typ Seek type.
     * @return New file position, relative to the start of file.
     */
    public long seekFile(SrvSession sess, TreeConnection tree, NetworkFile file, long pos, int typ)
    	throws java.io.IOException
    {
  	  	// Check if the file is a directory
    	
		if ( file.isDirectory())
			throw new AccessDeniedException();
      
    	// If the content channel is not open for the file then start a transaction
    	
    	AVMNetworkFile avmFile = (AVMNetworkFile) file;
    	
    	if ( avmFile.hasContentChannel() == false)
    		sess.beginTransaction( m_transactionService, true);
    	
		// Set the file position

		return file.seekFile(pos, typ);
    }

    /**
     * Set the file information for the specified file.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param name java.lang.String
     * @param info FileInfo
     * @exception java.io.IOException The exception description.
     */
    public void setFileInformation(SrvSession sess, TreeConnection tree, String name, FileInfo info)
    	throws java.io.IOException
    {
        // Check if the file is being marked for deletion, check if the file is writable
        
        if ( info.hasSetFlag(FileInfo.SetDeleteOnClose) && info.hasDeleteOnClose())
        {
        	// If this is not the head version then it's not writable
        	
        	AVMContext avmCtx = (AVMContext) tree.getContext();
        	if ( avmCtx.isVersion() != AVMContext.VERSION_HEAD)
        		throw new AccessDeniedException( "Store not writable, cannot set delete on close");
        }
    }

    /**
     * Start a new search on the filesystem using the specified searchPath that may contain
     * wildcards.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param searchPath File(s) to search for, may include wildcards.
     * @param attrib Attributes of the file(s) to search for, see class SMBFileAttribute.
     * @return SearchContext
     * @exception java.io.FileNotFoundException If the search could not be started.
     */
    public SearchContext startSearch(SrvSession sess, TreeConnection tree, String searchPath, int attrib)
    	throws java.io.FileNotFoundException
    {
    	// Access the AVM context
    	
    	AVMContext avmCtx = (AVMContext) tree.getContext();
    	
    	// DEBUG
    	
    	if ( logger.isDebugEnabled())
    		logger.debug("Start search path=" + searchPath);

    	// Check if the path is a wildcard search
    	
		sess.beginTransaction( m_transactionService, true);
    	SearchContext context = null;
    	
    	if ( WildCard.containsWildcards( searchPath))
    	{
	    	// Split the search path into relative path and search name
	    	
	    	String[] paths = FileName.splitPath( searchPath);
	    	
	    	// Build the store path to the folder being searched
	    	
	    	String storePath = buildStorePath( avmCtx, paths[0]);
	    	
	    	// Get the file listing for the folder
	    	
	    	AVMNodeDescriptor[] fileList = m_avmService.getDirectoryListingArray( avmCtx.isVersion(), storePath, false);
	    	
	    	// Create the search context
	    	
	    	if ( fileList != null) {

	    		// DEBUG
	    		
	    		if ( logger.isDebugEnabled())
	    			logger.debug("  Wildcard search returned " + fileList.length + " files");
	    		
	    		// Create the search context, wildcard filter will take care of secondary filtering of the
	    		// folder listing
	    		
	    		WildCard wildCardFilter = new WildCard( paths[1], false);
	    		context = new AVMSearchContext( fileList, attrib, wildCardFilter);
	    	}
    	}
    	else
    	{
    		// Single file/folder search, convert the path to a store path
    		
    		String storePath = buildStorePath( avmCtx, searchPath);
    		
    		// Get the single file/folder details
    		
    		AVMNodeDescriptor nodeDesc = m_avmService.lookup( avmCtx.isVersion(), storePath);
    		
    		if ( nodeDesc != null)
    		{
    			// Create the search context for the single file/folder
    			
    			context = new AVMSingleFileSearchContext( nodeDesc);
    		}
    		
    	}
    	
    	// Return the search context
    	
    	return context;
    }

    /**
     * Truncate a file to the specified size
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param file Network file details
     * @param siz New file length
     * @exception java.io.IOException The exception description.
     */
    public void truncateFile(SrvSession sess, TreeConnection tree, NetworkFile file, long siz)
    	throws java.io.IOException
    {
    	// If the content channel is not open for the file then start a transaction
    	
    	AVMNetworkFile avmFile = (AVMNetworkFile) file;
    	
    	if ( avmFile.hasContentChannel() == false)
    		sess.beginTransaction( m_transactionService, true);
    	
  	  	// Truncate or extend the file
  	  
  	  	file.truncateFile(siz);
  	  	file.flushFile();
    }

    /**
     * Write a block of data to the file.
     * 
     * @param sess Server session
     * @param tree Tree connection
     * @param file Network file details
     * @param buf byte[] Data to be written
     * @param bufoff Offset within the buffer that the data starts
     * @param siz int Data length
     * @param fileoff Position within the file that the data is to be written.
     * @return Number of bytes actually written
     * @exception java.io.IOException The exception description.
     */
    public int writeFile(SrvSession sess, TreeConnection tree, NetworkFile file, byte[] buf, int bufoff, int siz,
            				long fileoff)
    	throws java.io.IOException
    {
        // Check if the file is a directory

		if ( file.isDirectory())
			throw new AccessDeniedException();

    	// If the content channel is not open for the file then start a transaction
    	
    	AVMNetworkFile avmFile = (AVMNetworkFile) file;
    	
    	if ( avmFile.hasContentChannel() == false)
    		sess.beginTransaction( m_transactionService, true);
    	
		// Write the data to the file
		      
		file.writeFile(buf, siz, bufoff, fileoff);

		//  Return the actual write length

		return siz;
    }
    
    /**
     * Connection opened to this disk device
     * 
     * @param sess Server session
     * @param tree Tree connection
     */
    public void treeClosed(SrvSession sess, TreeConnection tree)
    {
        // Nothing to do
    }

    /**
     * Connection closed to this device
     * 
     * @param sess Server session
     * @param tree Tree connection
     */
    public void treeOpened(SrvSession sess, TreeConnection tree)
    {
        // Nothing to do
    }
}
