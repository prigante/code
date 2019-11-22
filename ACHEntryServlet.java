package ach;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ACHEntryServlet extends HttpServlet 
{
	private static final long serialVersionUID = 1L;
		
	static Logger logger = null;
       
	public void init(ServletConfig config) throws ServletException
	{
		super.init(config);
	}

	/**
	 * @see HttpServlet#service(HttpServletRequest request, HttpServletResponse response)
	 */
	public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException 
	{		
		try		 
		{				
			// Logger, it's also possible to extend it for database logging etc.
			logger = Logger.getAnonymousLogger();	
		    SimpleDateFormat sdfDate = new SimpleDateFormat("MM-dd-yyyy-HH.mm.ss");
		    Date now = new Date();
		    String strDate = sdfDate.format(now);			
	        FileHandler fh = new FileHandler("./webapps/ach/ACH-LOGS/ach-common-" + strDate + ".log");  
			logger.addHandler(fh);
			SimpleFormatter formatter = new SimpleFormatter();  
			fh.setFormatter(formatter); 
			logger.setUseParentHandlers(false);			
			logger.info("Start ACHEntryServlet service " + strDate);			
						
			String xmlString = req.getParameter("xml");
				
			// Load XML object
			XMLLoader xml = new XMLLoader();		     
		     
			if (xml.loadXML(xmlString, logger));
			{		     
				// Write stream to file if in Test mode
				if (xml.getMode().equalsIgnoreCase("T"))		
				{ 
					// Get parameter for XML Storage
					String xmlStore = req.getServletContext().getInitParameter("XMLStore");
					
					// Need to append timestamp
					String timeStamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());	
					
					PrintWriter testWriter = new PrintWriter(xmlStore + xml.getBankId() + "_" + timeStamp + ".xml", "UTF-8");
					testWriter.println(xmlString); 
					testWriter.flush();
					testWriter.close();	
			    	 
					PrintWriter nachaWriter = new PrintWriter(xmlStore + xml.getBankId() + "_" + timeStamp + "_nacha.txt", "UTF-8");
					nachaWriter.println(xml.getPayload()); 
					nachaWriter.flush();
					nachaWriter.close();			    	 
				}
						
				// Instantiate transmitter class 
				if (xml.getProtocol().equalsIgnoreCase("AS2"))
				{	
					logger.info("Calling AS2Transmitter");
					AS2Transmitter transmitter = new AS2Transmitter(); 					
					transmitter.transmit(xml, req, resp, logger);
				}	
				else if (xml.getProtocol().equalsIgnoreCase("HTTPS"))
				{	
					logger.info("Calling HTTPSTransmitter");
					HTTPSTransmitter transmitter = new HTTPSTransmitter(); 
					transmitter.transmit(xml, resp);
				}	
				else if (xml.getProtocol().equalsIgnoreCase("FTPS"))
				{	
					FTPSTransmitter transmitter = new FTPSTransmitter(); 
					
					String[] args = new String[6];
					args[0] = "-s";					// flag
					args[1] = xml.getUrl();			// hostname
					args[2] = xml.getSenderId();	// username
					args[3] = xml.getReceiverId();	// password		
					args[4] = xml.getPayload();		// remote file (if picking up)			
					args[5] = xml.getPayload();		// local file (if sending)		
					
					transmitter.transmit(args);
				}	
			}
			
			logger.info("End ACHEntryServlet");
			fh.flush();
			fh.close();
		} 
		catch (IOException e) 
		{
			// If Exception caught return Bad Request.			
			e.printStackTrace();
			logger.severe("IO Exception: " + e.getMessage());
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		} 
		catch (Exception e) 
		{
			// If Exception caught return Bad Request.
			e.printStackTrace();
			logger.severe("Exception: " + e.getMessage());
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);	
		}			 
	}
    
}// End ACHEntryServlet