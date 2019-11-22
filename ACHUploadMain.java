package com.vno.AchUpload;

import java.io.DataOutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Date;
import java.util.Vector;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.ibs.classes.utils.ReportMonitorLogger;
import com.ibs.common.SystemProperties;
import com.ibs.common.db.DbInterface;
import com.ibs.common.logger.appLogger;

/**
 * This is the main class for transmitting ACH payments to the as2 server. 
 * </BR></BR>
 * At the completion of this class the NACHA stream and config data appended to the end of the stream  
 * will be sent to an Apache Tomcat Web Server AS2 Servlet to be transmitted to the bank.
 * 
 * @author 	Patrick Christopher Rigante
 * 			February 22, 2017
 * 			CR 444
 */
public class ACHUploadMain 
{	
	private static final String PROCESS_INFO = "0";
	
	private static SystemProperties props 	= null;	
	private static DbInterface rsObj 		= null;	
	private static ACHUploadSQL sqlObject	= null;
	private static ReportMonitorLogger rml 	= null;
	
	private static String syskey 			= null;	
	private static String as2_ip			= null;	
	private static String herman			= null;
	
	private static boolean secure 			= false;
	
	// Global generic connection	
	private static Object connection 		= null;
		
	// Main	
	public static void main(String[] args) 
	{			
		try 
		{
			if (args.length < 1) 
			{
				System.out.println("No input Properties file specified - Quitting Upload!");
		    }
			else 
			{
				Date now = new Date();
				
				System.out.println("Start ACH Upload at " + now.toString());			
						
				herman = args[1];
				
				// Start Upload process
				ACHUploadMain proc = new ACHUploadMain(args[0]);				
			}
		}	
		catch (Exception e)
		{
			e.printStackTrace();
			System.out.println(e.getMessage());				
		}	
	}

	// Constructor
	public ACHUploadMain(String propfile)
	{	
		System.out.println("Constructor");	
		
		// Load Global Variables from Specified Properties File
		if (loadPropFileVals(propfile))							
			doProcess();	// Run the upload process		
	}
	
	/**
	 * This private method will load the property file
	 * and set the syskey variable.
	 *
	 * @param 
	 */	
	private boolean loadPropFileVals(String propertyFile) 
	{
		try 
		{			
			System.out.println("Start loadPropFileVals propertyFile = " + propertyFile);
			props 	= new SystemProperties(propertyFile);				
			syskey 	= SystemProperties.getProp("SRC_SYSKEY");	
			as2_ip	= SystemProperties.getProp("AS2_IP");
			appLogger.logMssg(appLogger.LOG_LEV_INFO, "syskey = " + syskey);
		}
		catch (Exception e)
		{
			e.printStackTrace();			
			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Error in loadPropFileVals()" + e.toString());	
			return false;
		}		
		
		return true;
		
	}// End loadPropFileVals 
	
	/**
	 * This private method process's all approved ach id's
	 * and create a NACHA stream to be sent to the
	 * Apache Tomcat Web Server AS2 Servlet.	
	 */	
	private void doProcess()
	{			
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "Start ACH Upload doProcess()");	
		
		try
		{
			// Make Connection to Database first
			rsObj = new DbInterface();
		
			if (rsObj != null)
			{
				// Monitor logging		
				rml = new ReportMonitorLogger(rsObj, syskey, "ACH", "ACH", "ACH_UPLOAD", "ACH_UPLOAD"); 				
				rml.doLogging();
				rml.logMonitorDetail("I", "Starting ACH Upload", PROCESS_INFO);	
		
				// This class object will handle most sql transactions
				sqlObject = new ACHUploadSQL(rsObj, syskey);
				
				// Check if process is allready running
				String isProcessRunning = sqlObject.getSysParamValue("ACH_TRANS_RUNNING");
				
				if (isProcessRunning.equalsIgnoreCase("Y"))
				{					
					appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "Process is already running");
					rml.logMonitorDetail("W", "ACH Upload found the Process Running Flag set to Y - Quitting Upload", "10805");
					return;
				}				
				
				// Update SYSTEM_PARAMETER for ACH_TRANS_RUNNING = Y
				sqlObject.updateSysParamforACHTransRunning("Y");
				
				// Check if process is paused
				String isProcessPaused = sqlObject.getSysParamValue("ACH_TRANS_PAUSED");
				
				if (isProcessPaused.equalsIgnoreCase("Y"))
				{					
					appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "Process is paused");
					rml.logMonitorDetail("W", "ACH Upload found the Process Paused Flag set to Y - Quitting Upload", "10806");
					return;
				}
				
				// Mark Voided Payments prior to getting all Approved ACH's
				sqlObject.markVoidedPayments();
				
				// Get all Approved ACH's
				String formattedAchIds = sqlObject.getApprovedAchIds();
				
				// Stop processing if there are no approved payments
				if (formattedAchIds == null || formattedAchIds.length() == 0)
				{					
					appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "No approved payments right now to process");
					rml.logMonitorDetail("I", "No approved payments right now to process", PROCESS_INFO);
					return;
				}
				
				// Check if the AS2 server is Up and Running, If not do nothing
				Boolean result = sendOutboundMessage("Testing Connection", "ConnectionTestServlet");
		
				if (result)				
				{			
					// Need to check that the approved payments have not been tampered with
					if (verifyIntegrity(formattedAchIds))
					{							
						// Weed out any payments with bad routing and / or account numbers
						formattedAchIds = checkData(formattedAchIds);
						
						// Need to split up selected ach id's by bank				
						// Get Vector of Bank Id's for selected ACH Id's	
						Vector vBanks = sqlObject.getBankIds(formattedAchIds);		
								
						// Hold bank id
						String tempBankId = null;
						String tempAchIds = null;	 	            	
			        
			            if (vBanks != null && vBanks.size() > 0)
			            {  
							// Loop through Bank Id's
				        	for (int i=0; i<vBanks.size(); i++)
				        	{	
				        		Vector innerVector = (Vector) vBanks.elementAt(i);	            		
				
				        		// Prime tempBankId with bank id of first vector record	
				        		if (i==0)
				        			tempBankId = (String) innerVector.elementAt(0);	  
				        				            			      
				        		// Check if tempBankId = bank id from record
				    			if (tempBankId.equalsIgnoreCase((String) innerVector.elementAt(0)))
				    			{            			            		
				    				// Load ach id's for bank
				        			if (tempAchIds == null || tempAchIds == "")		            			
				        				tempAchIds = "'" + (String) innerVector.elementAt(1) + "'";	            			
				        			else		            			
				        				tempAchIds = tempAchIds + ", '" + (String) innerVector.elementAt(1) + "'";
				        					            			
				        			// If last record in vector 
				            		if (i==(vBanks.size()-1))
				            			sendNACHAStream(tempBankId, tempAchIds);			            		
				    			}
				    			else
				    			{            				
				    				// If last record in set
				    				sendNACHAStream(tempBankId, tempAchIds);        				
				    				
				    				// Clear tempAchIds and re-set tempBankId to next bank id
				    				tempAchIds = "";
				    				tempBankId = (String) innerVector.elementAt(0);
				    			
				    				// Check if tempBankId = bank id from record
				        			if (tempBankId.equalsIgnoreCase((String) innerVector.elementAt(0)))
				        			{	
				        				// Load ach id's for bank
				            			if (tempAchIds == null || tempAchIds == "")	    	            			
				            				tempAchIds = "'" + (String) innerVector.elementAt(1) + "'";	    	            			
				            			else	    	            				
				            				tempAchIds = tempAchIds + ", '" + (String) innerVector.elementAt(1) + "'";	    	            			    	            			
				        			}	                			
				        			
				        			// If last record in vector 
				            		if (i==(vBanks.size()-1))
				            			sendNACHAStream(tempBankId, tempAchIds);  			    			
				    			}		    						    			
				        	}
			            }			            
					}		
					else
					{					
						// Failed Integrity Check
						appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Failed Integrity Check!");
										
						// Closeday monitoring		
						rml.logMonitorDetail("F", "ACH Upload: Failed Integrity Check! Quitting ACH Upload", "10800");	
						
						throw new Exception("Failed Integrity Check!");
					}					
				} 
				else
				{
					// AS2 server not available
					appLogger.logMssg(appLogger.LOG_LEV_ERROR, "AS2 server not available!");
					
					// Closeday monitoring		
					rml.logMonitorDetail("F", "ACH Upload: AS2 server not available! Quitting ACH Upload", "10807");
					
					throw new Exception("AS2 server not available!");
				}		
			} 
			else
			{
				// Could not establish connection to Oracle Database
				appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Oracle Database not available!");
				
				throw new Exception("Oracle Database Connection Failed!");
			}
		} 
		catch (Exception e) 		
		{	
			e.printStackTrace();			
			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Exception in Ach Upload! Caught in doProcess() " + e.toString());			
		}
		finally
		{
			// Always set System Parameter for ACH_TRANS_RUNNING to N
			try 
			{
				sqlObject.updateSysParamforACHTransRunning("N");
				
				// Closeday monitoring		
				rml.logMonitorDetail("I", "ACH Upload Complete", PROCESS_INFO);
				
				// Set Closeday Monitoring Severity
				rml.logMonitorSeverity();
			} 
			catch (Exception e) 
			{				
				e.printStackTrace();
			}	
			
			appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "ACH Upload Complete");
		}		
		
	}// End doProcess
	
	private String checkData(String formattedAchIds) throws Exception 
	{		
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "Start checkData() Testing Routing & Account Number for ACH Id's: " + formattedAchIds);
		
		// Get all info here, loop, test, put passed ach id in bucket
		Vector testVector = sqlObject.getTestInfo(formattedAchIds);
		
		String testedAchIds = null;
		
		if (testVector != null && testVector.size() > 0)
		{						
			for (int i=0; i < testVector.size(); i++)
			{	
				Vector innerVector = (Vector) testVector.elementAt(i); 
			
				String achId = (String) innerVector.elementAt(6); 
				appLogger.logMssg(appLogger.LOG_LEV_INFO, "Testing Routing & Account Number for ACH Id: " + achId);
						
				// Need to test for length of 9 and numeric only
				// If either test fails update close day monitor and reject payment
				String routingNumber = (String) innerVector.elementAt(0);
				
				// If value is -1 then Decryption function failed indicating bad data
				if (routingNumber.equalsIgnoreCase("-1"))
				{
					appLogger.logMssg(appLogger.LOG_LEV_INFO, "Routing Number has bad data for ACH Id: " + achId);
					
					// Insert into closeday monitor
					rml.logMonitorDetail("E", "ACH Upload: Routing Number has bad data! " + innerVector.elementAt(3) + " " + innerVector.elementAt(2) + " ACH ID: " + innerVector.elementAt(6), "10803");	
					
		        	// Update Payment Status to Error
					sqlObject.updatePaymentHeaderStatus((String) innerVector.elementAt(6), "ERROR");
					
					continue;					
				}
								
				if (routingNumber.length() == 9)
				{		
				    try
			        {
			            // Checking valid integer using parseInt() method
			            Integer.parseInt(routingNumber);           
			        } 
			        catch (NumberFormatException e) 
			        {
			        	appLogger.logMssg(appLogger.LOG_LEV_INFO, "Routing Number is Not Numeric for ACH Id: " + achId);
			        	
			        	// Insert into closeday monitor
			        	rml.logMonitorDetail("E", "ACH Upload: Routing Number is Not Numeric! " + innerVector.elementAt(3) + " " + innerVector.elementAt(2) + " ACH ID: " + innerVector.elementAt(6), "10801");
			        	
			        	// Update Payment Status to Error
			        	sqlObject.updatePaymentHeaderStatus((String) innerVector.elementAt(6), "ERROR"); 
			        	
			        	continue;
			        }    
				} 
				else
				{
					appLogger.logMssg(appLogger.LOG_LEV_INFO, "Routing Number is Not Nine Digits for ACH Id: " + achId);
					
					// Insert into closeday monitor
					rml.logMonitorDetail("E", "ACH Upload: Routing Number is Not Nine Digits! " + innerVector.elementAt(3) + " " + innerVector.elementAt(2) + " ACH ID: " + innerVector.elementAt(6), "10802");	
					
		        	// Update Payment Status to Error
					sqlObject.updatePaymentHeaderStatus((String) innerVector.elementAt(6), "ERROR");
					
					continue;
				}
				
				// Check if Routing Number is zero	
				if (routingNumber.equalsIgnoreCase("0"))
				{	
					appLogger.logMssg(appLogger.LOG_LEV_INFO, "Routing Number is Not Valid for ACH Id: " + achId);
					
					// Insert into closeday monitor
					rml.logMonitorDetail("E", "ACH Upload: Routing Number is Not Valid! " + innerVector.elementAt(3) + " " + innerVector.elementAt(2) + " ACH ID: " + innerVector.elementAt(6), "10803");	
					
		        	// Update Payment Status to Error
					sqlObject.updatePaymentHeaderStatus((String) innerVector.elementAt(6), "ERROR");
		        	
		        	continue;
				}
		
				// Check if Account Number is zero
				String accountNumber = (String) innerVector.elementAt(1);
				
				// If value is -1 then Decryption function failed indicating bad data
				if (accountNumber.equalsIgnoreCase("-1"))
				{
					appLogger.logMssg(appLogger.LOG_LEV_INFO, "Account Number has bad data for ACH Id: " + achId);
					
					// Insert into closeday monitor
					rml.logMonitorDetail("E", "ACH Upload: Account Number has bad data! " + innerVector.elementAt(3) + " " + innerVector.elementAt(2) + " ACH ID: " + innerVector.elementAt(6), "10804");	
					
		        	// Update Payment Status to Error
					sqlObject.updatePaymentHeaderStatus((String) innerVector.elementAt(6), "ERROR");
					
					continue;					
				}
				
				if (accountNumber.equalsIgnoreCase("0"))
				{	
					appLogger.logMssg(appLogger.LOG_LEV_INFO, "Account Number is Not Valid for ACH Id: " + achId);
					
					// Insert into closeday monitor
					rml.logMonitorDetail("E", "ACH Upload: Account Number is Not Valid! " + innerVector.elementAt(3) + " " + innerVector.elementAt(2) + " ACH ID: " + innerVector.elementAt(6), "10804");	
					
		        	// Update Payment Status to Error
					sqlObject.updatePaymentHeaderStatus((String) innerVector.elementAt(6), "ERROR");
		        	
		        	continue;
				}
				
				// If survived up to here then add to string
				appLogger.logMssg(appLogger.LOG_LEV_INFO, "ACH Id: " + achId + " has passed");
				
				if (testedAchIds == null)
					testedAchIds = "'" + achId + "'";
				else					
					testedAchIds = testedAchIds + ", '" + achId + "'";
			}						
		}
		
		return testedAchIds;
	}

	/**
	 * This private method will be called once all selected ach id's have been split by bank id.
	 * The string of selected ach id's will be passed to the NACHA file creator class.	
	 * 
	 * @param tempBankId	The Bank to which the ach id's will be passed to.
	 * @param tempAchIds	The string of ach id's per bank id to be passed to the NACHA file creator.
	 * @throws Exception 
	 */	
	private void sendNACHAStream(String tempBankId, String tempAchIds) throws Exception
	{				
		appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "Start sendNACHAStream()");		
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "sendNACHAStream tempBankId = " + tempBankId);
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "sendNACHAStream tempAchIds = " + tempAchIds);
		
		// Instantiate NACHA File Creator class here
		NachaFileCreator fileCreator = new NachaFileCreator(rsObj, syskey, sqlObject);				
	
		// Build NACHA message
		String stream = fileCreator.doProcess(tempBankId, tempAchIds);			
						
		// Because the NACHA File Creator only can send back a string
		// If the stream string is "FAILED" then do not continue on 
		// with sending the stream to the bank. Still need to insert and update
		// all transmission tables indicating error.
		if (stream.equalsIgnoreCase("FAILED"))
		{	
			// Update Closeday Monitor that the NACHA message could not be created and end			
			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Nacha file creator failed to create stream!");
			
			// Closeday monitoring		
			rml.logMonitorDetail("F", "ACH Upload: Nacha file creator failed to create stream! Quitting ACH Upload", "10808");
			
			throw new Exception("Nacha file creator failed to create stream!");			
		}
		else
		{	
			// NACHA message is ready to send

			// Get next system control number for ACH_TRANS_ID
			int achTransId = sqlObject.getSysControl();				
			appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "sendStream() achTransId = " + achTransId);
		
			// Need to get Payment Count before passing to send stream method
			String[] parts = tempAchIds.split(",");				
			int paymentCount = parts.length;
			
			// Insert new record into ACH_TRANSMIT_HEADER
			sqlObject.insertTransmitHeader("IN_TRANSIT", tempBankId, achTransId, paymentCount);
			
    		// Insert all ACH detail records into ACH_TRANSMIT_DETAIL
			sqlObject.insertTransmitDetail(tempBankId, achTransId, tempAchIds);
			
			// Update TRANSMISSION_NUM with ACH_TRANS_ID in ACH_PAYMENT_HEADER    		
			sqlObject.updatePaymentHeaderTransNumber(achTransId, tempAchIds);
    		
			// Pack stream into xml 			
			String message = URLEncoder.encode(createXML(tempBankId, tempAchIds, achTransId, stream), "UTF-8");
		
			// Send message to AS2 server
			boolean successful = sendOutboundMessage(message, "ACHEntryServlet");
			
		    // If there is a response code AND that response code is OK					
			if (successful)			    
		    {
		        // OK			    	
		    	appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "Send Stream Response code is good!");	
		    	
				// Closeday monitoring		
				rml.logMonitorDetail("I", "ACH Upload: Send Stream Response code is good!", PROCESS_INFO);
			
		    	// Update ACH_TRANSMIT_HDR with success message
		    	sqlObject.updateTransmitHeader("Transmission to Bank was successful", achTransId, "ACCEPTED");
		    	
				// Update Transmit Detail
		    	sqlObject.updateTransmitDetail("ACCEPTED", achTransId);
				
		    	// Update ACH_PAYMENT_HEADER Set PAYMENT_STATUS to Paid
		    	sqlObject.updatePaymentHeaderStatus(tempAchIds, "PAID");		
		    	
		    	// Set up email
		    	ACHUploadEmail.insertEmailBatch(achTransId, rsObj, syskey, rml, sqlObject);	
		    	
		    	// Insert new record in to Report Instance so as to kick off report process
		    	insertReportInstance(achTransId);		
		    }
			else
			{
				// Update Transmit Detail
		    	sqlObject.updateTransmitDetail("FAILED", achTransId);

				// Update Headers to FAILED
		    	sqlObject.updatePaymentHeaderStatus(tempAchIds, "FAILED");				
				
		        // Server returned HTTP error code.	
		    	if (secure)
		    	{	
		    		if (((HttpsURLConnection) connection).getResponseCode() == HttpsURLConnection.HTTP_NOT_FOUND) // 404
		    		{	
		    			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Could not reach Bank URL! " + ((HttpsURLConnection) connection).getResponseCode() + " - " + ((HttpsURLConnection) connection).getResponseMessage());
		    			sqlObject.updateTransmitHeader("Could not establish connection with Bank URL", achTransId, "FAILED");
		    			rml.logMonitorDetail("F", "Could not establish connection with Bank URL", "10810");
		    		}
		    		else if (((HttpsURLConnection) connection).getResponseCode() == HttpsURLConnection.HTTP_INTERNAL_ERROR) // 500
		    		{
		    			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Returned HTTP answer is not a valid MDN! " + ((HttpsURLConnection) connection).getResponseCode() + " - " + ((HttpsURLConnection) connection).getResponseMessage());
		    			sqlObject.updateTransmitHeader("Bank received file but returned HTTP answer is not a valid MDN", achTransId, "FAILED");	
		    			rml.logMonitorDetail("E", "Bank received file but returned HTTP answer is not a valid MDN", "10811");
		    		}
		    		else if (((HttpsURLConnection) connection).getResponseCode() == HttpsURLConnection.HTTP_NOT_IMPLEMENTED) // 501
		    		{
		    			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Returned MDN indicates that there is an error! " + ((HttpsURLConnection) connection).getResponseCode() + " - " + ((HttpsURLConnection) connection).getResponseMessage());
		    			sqlObject.updateTransmitHeader("Bank received file but returned MDN indicates that there is an error", achTransId, "FAILED");
		    			rml.logMonitorDetail("E", "Bank received file but returned MDN indicates that there is an error", "10812");
		    		}
		    		else if (((HttpsURLConnection) connection).getResponseCode() == HttpsURLConnection.HTTP_CONFLICT) // 409
		    		{
		    			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Error caught in AS2 Transmitter, Bank did not recieve file! " + ((HttpsURLConnection) connection).getResponseCode() + " - " + ((HttpsURLConnection) connection).getResponseMessage());
		    			sqlObject.updateTransmitHeader("Bank did not recieve file and there is an error", achTransId, "FAILED");
		    			rml.logMonitorDetail("F", "Bank did not recieve file and there is an error", "10813");
		    		}
		    	}
		    	else
		    	{	
		    		if (((HttpURLConnection) connection).getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND)
		    		{				    		
		    			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Could not reach Bank URL! " + ((HttpURLConnection) connection).getResponseCode() + " - " + ((HttpURLConnection) connection).getResponseMessage());
		    			sqlObject.updateTransmitHeader("Could not establish connection with Bank URL", achTransId, "FAILED");
		    			rml.logMonitorDetail("F", "Could not establish connection with Bank URL", "10810");
		    		}
		    		else if (((HttpURLConnection) connection).getResponseCode() == HttpURLConnection.HTTP_INTERNAL_ERROR)
		    		{	
		    			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Returned HTTP answer is not a valid MDN! " + ((HttpURLConnection) connection).getResponseCode() + " - " + ((HttpURLConnection) connection).getResponseMessage());
		    			sqlObject.updateTransmitHeader("Bank received file but returned HTTP answer is not a valid MDN", achTransId, "FAILED");	
		    			rml.logMonitorDetail("E", "Bank received file but returned HTTP answer is not a valid MDN", "10811");
		    		}	
		    		else if (((HttpURLConnection) connection).getResponseCode() == HttpURLConnection.HTTP_NOT_IMPLEMENTED)
		    		{	
		    			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Returned MDN indicates that there is an error! " + ((HttpURLConnection) connection).getResponseCode() + " - " + ((HttpURLConnection) connection).getResponseMessage());
		    			sqlObject.updateTransmitHeader("Bank received file but returned MDN indicates that there is an error", achTransId, "FAILED");	
		    			rml.logMonitorDetail("E", "Bank received file but returned MDN indicates that there is an error", "10812");
		    		}
		    		else if (((HttpURLConnection) connection).getResponseCode() == HttpURLConnection.HTTP_CONFLICT)
		    		{	
		    			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Error caught in AS2 Transmitter, Bank did not recieve file! " + ((HttpURLConnection) connection).getResponseCode() + " - " + ((HttpURLConnection) connection).getResponseMessage());
		    			sqlObject.updateTransmitHeader("Bank did not recieve file and there is an error", achTransId, "FAILED");	
		    			rml.logMonitorDetail("F", "Bank did not recieve file and there is an error", "10813");
		    		}
		    	}				
			}	
		}	
		
	}// End sendNACHAStream

	/**
	 * This method will create the XML Config file with nacha payload.
	 *  
	 * @param tempBankId 	The bank id of the file that was just created	
	 * @param tempAchIds 
	 * @param achTransId 
	 * @param nacha 
	 * @return String		XML Structure as String to be passed to AS2 Tomcat Servlet
	 */
	private static String createXML(String tempBankId, String tempAchIds, int achTransId, String nacha) throws Exception
	{			
		appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "Start createXML() tempBankId = " + tempBankId);
		
		String output = null;
				
		try 
		{
			// Get bank info			
			Vector bankFile = sqlObject.getBankTransmitInfo(tempBankId);
			Vector bankRow 	= null;
	 
	        if (bankFile != null && bankFile.size()>0)
	        	bankRow = (Vector) bankFile.firstElement();
			
	        appLogger.logMssg(appLogger.LOG_LEV_INFO, "createXML() bankRow = " + bankRow);
	        
			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

			// root elements
			Document doc = docBuilder.newDocument();
			Element rootElement = doc.createElement("config");
			doc.appendChild(rootElement);

			// bank elements
			Element config = doc.createElement("bank");
			rootElement.appendChild(config);

			// set attribute to bank element
			Attr attr = doc.createAttribute("id");
			attr.setValue(tempBankId.trim());
			config.setAttributeNode(attr);		

			// Check if url has protocol
			String urlCheck = (String) bankRow.elementAt(2);
			String domain = urlCheck.substring(0, urlCheck.indexOf(":"));
			
			if (domain == null || domain.length() == 0)
			{
				// Has no domain
				urlCheck = "https://" + urlCheck;
			}
			
			appLogger.logMssg(appLogger.LOG_LEV_INFO, "createXML() after urlCheck = " + urlCheck);
			
			// url element
			Element url = doc.createElement("url");
			url.appendChild(doc.createTextNode(urlCheck));
			config.appendChild(url);
			
			// senderId element
			Element senderId = doc.createElement("senderId");
			senderId.appendChild(doc.createTextNode((String) bankRow.elementAt(3)));
			config.appendChild(senderId);

			// receiverId element
			Element receiverId = doc.createElement("receiverId");
			receiverId.appendChild(doc.createTextNode((String) bankRow.elementAt(4)));
			config.appendChild(receiverId);
			
			// sender alias element
			Element sender_alias = doc.createElement("sender_alias");
			sender_alias.appendChild(doc.createTextNode((String) bankRow.elementAt(5)));
			config.appendChild(sender_alias);

			// receiver alias element
			Element receiver_alias = doc.createElement("receiver_alias");
			receiver_alias.appendChild(doc.createTextNode((String) bankRow.elementAt(6)));
			config.appendChild(receiver_alias);
			
			// achIds element
			Element achIds = doc.createElement("achIds");
			achIds.appendChild(doc.createTextNode(tempAchIds));
			config.appendChild(achIds);	

			// transId element
			Element transId = doc.createElement("transId");
			transId.appendChild(doc.createTextNode(Integer.toString(achTransId)));
			config.appendChild(transId);

			// mode element
			String testMode = sqlObject.getSysParamValue("ACH_MODE");
			Element mode = doc.createElement("mode");
			mode.appendChild(doc.createTextNode(testMode));
			config.appendChild(mode);

			// protocol element
			Element protocol = doc.createElement("protocol");
			protocol.appendChild(doc.createTextNode((String) bankRow.elementAt(1)));
			config.appendChild(protocol);
			
			// payload element
			Element payload = doc.createElement("payload");
			payload.appendChild(doc.createTextNode(nacha));
			config.appendChild(payload);

			// prefix element
			Element prefix = doc.createElement("prefix");
			prefix.appendChild(doc.createTextNode((String) bankRow.elementAt(7)));
			config.appendChild(prefix);
			
			// Write the content into xml
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			
			StringWriter writer = new StringWriter();
			transformer.transform(new DOMSource(doc), new StreamResult(writer));			
			output = writer.getBuffer().toString();			
			appLogger.logMssg(appLogger.LOG_LEV_INFO, "End createXML output = " + output);
		} 
		catch (ParserConfigurationException pce) 
		{
			pce.printStackTrace();				
			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "createXML ParserConfigurationException: " + pce.getMessage());
		} 
		catch (TransformerException tfe) 
		{
			tfe.printStackTrace();				
			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "createXML TransformerException: " + tfe.getMessage());
		}	
		catch (Exception e) 
		{
			e.printStackTrace();				
			appLogger.logMssg(appLogger.LOG_LEV_ERROR, "createXML Exception: " + e.getMessage());
		}	
		
		return output;
	
	}// End createXML
	
	private static Boolean sendOutboundMessage(String message, String servletName) throws Exception
	{			
		Boolean result = false;

		// Instantiate the URL object with the target URL of the resource to request
		String request = as2_ip + "/ach/" + servletName;			
		URL url = new URL(request);				
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "sendOutboundMessage New url object instantiated request = " + request);
	    
		// Determine protocol and if secure connection
		String protocol = as2_ip.substring(0, as2_ip.indexOf(":"));
					
		if (protocol.equalsIgnoreCase("https"))
		{	
			secure = true;
			appLogger.logMssg(appLogger.LOG_LEV_INFO, "Sending message via HTTPS connection secure = " + secure);
		}	
		else
			appLogger.logMssg(appLogger.LOG_LEV_INFO, "Sending message via HTTP connection secure = " + secure);
			
	    // Instantiate the HttpsURLConnection with the URL object - A new
	    // connection is opened every time by calling the openConnection
	    // method of the protocol handler for this URL.
	    // This is the point where the connection is opened.
	    if (secure)			
	    	connection = (HttpsURLConnection) url.openConnection();					    
	    else
	    	connection = (HttpURLConnection) url.openConnection();	
	    		    
	    appLogger.logMssg(appLogger.LOG_LEV_INFO, "sendOutboundMessage Opening connection");
	    
	    // Set connection output to true
	    ((URLConnection) connection).setDoOutput(true);
	    
	    // Instead of a GET, we're going to send using method = "POST"
	    if (secure)
	    	((HttpsURLConnection) connection).setRequestMethod("POST");
	    else
	    	((HttpURLConnection) connection).setRequestMethod("POST");
	    
	    String urlParameters = null;
	    
	    if (servletName.equalsIgnoreCase("ConnectionTestServlet"))
	    	urlParameters = "msg=" + message;
	    else	
	    	urlParameters = "xml=" + message + "&herman=" + herman; 
	  	    
		// Send post request
		((URLConnection) connection).setDoOutput(true);
		DataOutputStream wr = new DataOutputStream(((URLConnection) connection).getOutputStream());
		wr.writeBytes(urlParameters);
		wr.flush();
		wr.close();
	    
		int responseCode = 0;
		
		// If response code is 200 or 202 then message was sent and received
		if (secure)
		{	
			responseCode = ((HttpsURLConnection) connection).getResponseCode();
			
	    	if ((responseCode == HttpsURLConnection.HTTP_ACCEPTED) || (responseCode == HttpsURLConnection.HTTP_OK))	
	    		result = true;
		}
		else
		{	
			responseCode = ((HttpURLConnection) connection).getResponseCode();
			
	    	if ((responseCode == HttpURLConnection.HTTP_ACCEPTED) || (responseCode == HttpURLConnection.HTTP_OK))	
	    		result = true;	
		}
	
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "sendOutboundMessage completed result = " + result);
   		
		return result;
	
	}// End sendOutboundMessage

	/**
	 * This private method will check for data integrity.
	 * Hash of data is checked against stored hash inserted by backend program.
	 *
	 * @param formattedAchIds
	 * @return boolean
	 */	
	private boolean verifyIntegrity(String formattedAchIds) throws Exception 
	{			
		appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "Start verifyIntegrity()");
		
		// Need to loop through all Approved payments and if check sum fails to match
		// then mark as ERROR. Continue matching all check sums but still do not send batch.
		
		int errorCount 		= 0;
		
		String checkHash 	= null;
		String storedHash	= null;
		String checkSql 	= null;
		
		StringBuffer sql = new StringBuffer();
		
		sql.append("SELECT VENNO, TO_CHAR(PAYMENT_TOTAL, '99999999999.00'), VENDOR_ROUTING_NUM, VENDOR_DDA_NUM, CHECK_SUM, ACH_ID ");
		sql.append("FROM ACH_PAYMENT_HEADER ");
		sql.append("WHERE SYS_KEY1 = '" + syskey + "' AND ACH_ID IN (" + formattedAchIds + ") ");
		sql.append("AND PAYMENT_STATUS = 'APPROVED' ");				
				
		Vector hashVect = rsObj.getResultVector(sql.toString());		
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "verifyIntegrity() hashVect = " + hashVect);
		
        if (hashVect != null && hashVect.size() > 0)
        {         	
        	for (int i=0; i < hashVect.size(); i++)
        	{	
        		Vector innerVector = (Vector) hashVect.elementAt(i);
        		 
        		// ACH ID	
        		String achId = (String) innerVector.elementAt(5);
        		appLogger.logMssg(appLogger.LOG_LEV_INFO, "verifyIntegrity() Matching Check sum for: " + achId);
        		
        		// Vendor Number
        		String venno = (String) innerVector.elementAt(0);
        		
        		if (venno.length() < 6)
        		{	
        			for (int j = venno.length(); j < 6; j++)
        			{	
        				venno = "0" + venno;
        			}
        		}	
        		        		
        		// Amount
        		String amount = (String) innerVector.elementAt(1);
        		amount = amount.trim();
          		
        		if (amount.length() < 14)
        		{
        			for (int k = amount.length(); k < 14; k++)
        			{	
        				amount = "0" + amount;
        			}       			
        		}
        		
        		// Routing Number
        		String route = (String) innerVector.elementAt(2);
           		
        		// DDA
        		String dda = (String) innerVector.elementAt(3);
  
        		checkHash	= venno + amount + route + dda;
        		storedHash 	= (String) innerVector.elementAt(4);            	
        		checkSql 	= "SELECT CHECKSALTEDHASH ('" + checkHash + "', '" + storedHash + "') FROM DUAL";        		
        		        		
        		Vector checkVect 		= rsObj.getResultVector(checkSql);        		
        		Vector returnValue 		= (Vector) checkVect.elementAt(0);         		
        		String strReturnValue	= (String) returnValue.elementAt(0);        		
        		appLogger.logMssg(appLogger.LOG_LEV_INFO, "verifyIntegrity() strReturnValue = " + strReturnValue);
        		
        		// If check sum returns false
    			if (strReturnValue.equalsIgnoreCase("false")) 
    			{	    				
    				appLogger.logMssg(appLogger.LOG_LEV_ERROR, "verifyIntegrity() failed! checkHash = " + checkHash + " storedHash = " + storedHash);
    				
    				// Update payment status to ERROR    	 
    				sqlObject.updatePaymentHeaderStatus(achId, "ERROR");
    				
    				// Closeday monitoring		
    				rml.logMonitorDetail("F", "Check Sum mismatch for ACH ID " + achId, "10800");	
    				
    				// Increment error counter
    				errorCount += 1;
    			}    			
        	}      
        }
        
        if (errorCount == 0)
        {	        
        	appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "End verifyIntegrity() Returning true - all approved payments have passed the integrity check");        
        	return true;
        }
        else
        {
        	appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "End verifyIntegrity() Returning false - some or all approved payments have been tampered with");        
        	return false;        	
        }
	
	}// End verifyIntegrity
	
	private static void insertReportInstance(int achTransId) throws Exception 
	{
		appLogger.logMssg(appLogger.LOG_LEV_CONTROL, "Start insertReportInstance()");
				
		RptInstance report = new RptInstance();
		
		report.setSYS_KEY1(syskey);
		report.setINSTANCEID(sqlObject.getNextReportInstanceId());
		report.setSYNC_ID(String.valueOf(achTransId));
		report.setINSTANCE_DESC("ACH Transmission Report");
		report.setREPORTID("ACHTRANS");
		report.setFORMAT_STATUS("P");					
		report.setHANDLER_TYPE("S");
		report.setSTATUSFLAG("S");					
		report.setACTIVE_FLAG("Y");
		report.setRPTCREATE_USER("ACH_UPLOAD");		
		report.setMODIFY_USER("ACH_UPLOAD");
		report.setCREATE_USER("ACH_UPLOAD");
		report.setAGENTID(sqlObject.getReportAgentId());
		
		String insertSql = report.insertRptInstance();
		
		int noofRowsInserted = rsObj.executeUpdate(insertSql);
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "noofRowsUpdated = " + Integer.toString(noofRowsInserted));			
	
	}// End insertReportInstance
	
}// End ACHUploadMain Class