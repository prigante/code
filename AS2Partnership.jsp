<!DOCTYPE html>

<html>

<%
	Logger.log("ACH Bank Info JSP", "AS2Partnership.jsp", "Start JSP for ACH Bank Info", Logger.FATAL, Logger.LOG_COMMON);
	response.setHeader("Cache-Control","no-cache"); //HTTP 1.1
	response.setHeader("Pragma","no-cache"); //HTTP 1.0
	response.setDateHeader ("Expires", 0); //prevents caching at the proxy server	
%>

<%@ page import="java.util.*,java.net.URLEncoder"%>
<%@ page import="java.io.*"%>
<%@ page import="epm.util.*"%>
<%@ page import="epm.objects.*"%>
<%@ page import="epm.dbutil.*"%>
<%@ page session="true" errorPage="error.jsp"%>

<jsp:useBean id="sException"		scope="request" class="java.lang.String"/>
<jsp:useBean id="bankIdVector"		scope="request" class="java.util.Vector"/>

<%	
session.setAttribute("screenTitle", "ACH Partnership Info");

try
{
%>

<head>	
	
	<link rel="stylesheet" href="<%=Constant.BASE_URL%>/StyleSheet.css" type="text/css">
	
	<link rel="stylesheet" href="/epm/jquery-ui.css">
	<link rel="stylesheet" href="/epm/css/QueryScreen.css">
			
	<%="<script src='" + Constant.SCRIPT_URL + "/Constants.js'></SCRIPT>"%>
	<%="<script src='" + Constant.SCRIPT_URL + "/genfunctions.js'></SCRIPT>"%>
	<%="<script src='" + Constant.SCRIPT_URL + "/JSGeneralFunctions.js'></SCRIPT>"%>
	<%="<script src='" + Constant.SCRIPT_URL + "/MsgConstant.js'></SCRIPT>"%>
	
	<%="<script src='" + Constant.SCRIPT_URL + "/AS2Partnership.js'></SCRIPT>"%>
	
	<script language="Javascript" src="/epm/scripts/jquery-1.11.0.min.js"></script>
  	<script language="Javascript" src="/epm/scripts/jquery-ui.min.js"></script>
  	
  	
  	  <script>
	$(document).ready(function() {
		$( function() {
		    $( "#senderCertExpDt" ).datepicker({
		    	beforeShow: function( input, inst){
		    		$(inst.dpDiv).addClass('whereClause');
		    	    $(inst.dpDiv).addClass('datePicker');
		    	    }
		    
		    });
		    
		    
		    $( "#receiverCertExpDt" ).datepicker();		    
		  } );      
      });
    </script>
  	
	
	<style>
		#formDiv
		{		
			font-family: Arial, Verdana, sans-serif;		
			margin: 4px 50px;
			width: 860px;		
		}	
		
		#configDetails, #as2Details, #bankDetails 
		{
			margin: 4px;
		}
	
		label
		{
			display: block;
			clear: left;
			float: left;
			font-size: 0.8em;
			text-align: right;
			margin: 4px 6px;
			width: 35%;
		}
		
		input
		{
			display: block;
			float: left;
			font-size: 0.9em;
			width: 58%;
			margin: 3px 0px;
		}
		
		#bankDetails input		
		{
			display: block;
			float: left;
			font-size: 0.9em;
			width: 40%;
			margin: 3px 0px;
		}
				
		select
		{
			display: block;				
			font-size: 0.9em;
			margin-bottom: 2px;
			width: 80px;
		}
		
		button
		{		
			display: block;		
			margin: 5px auto;
		}		
		
		p
		{
			font-size: 0.8em;
			margin-top: 0px;
			margin-bottom: 4px;
			text-align: center;		
		}
		
		p.whiteFont
		{
			color: white;				
		}
		
		p.greenFont
		{
			color: green;		
		}
		
		p.redFont
		{
			color: red;	
			font-weight: bold;
		}	
		
		.black_overlay
		{
	       	display: none;
	       	position: absolute;
	       	top: 0%;
	       	left: 0%;
	       	width: 100%;
	       	height: 620px;
	       	background-color: #0069b3;
	       	z-index:1001;
	       	-moz-opacity: 0.8;
	       	opacity:.80;
	       	filter: alpha(opacity=80);
	   	}
	   	
	   	/* Style for pop up on Transmit screen */
	   	.white_content2 
	   	{
	    	display: none;
	       	position: absolute;
	       	top: 20%;
	       	left: 20%;
	       	width: 350px;
	       	height: 130px;
	       	padding: 0px;
	       	border: 16px solid #FFC174;
	       	background-color: white;
	       	z-index: 1002;
	       	overflow: hidden;
	   	}
	   	
	   			#table {
    display: table;
}
.tr {
    display: table-row;

}
.td {
    display: table-cell;
    border: #000000 solid 0px;
}
	   	
	   	
	</style>
	
</head>

<body class="clsBody" marginwidth="0" marginheight="0" leftmargin="0" topmargin="0" onload="" onClick="<%=HideAllDivs((Vector)session.getAttribute("menuVec"))%>">
<%@ include file = "/epm/dynmenu.jsp"%>

	<div id="formDiv" border =1>
		<form name="frmAchAs2Config" id="frmAchAs2Config">
			<fieldset class="clsFieldSet">
				<legend class="LblFont">ACH AS2 Configuration</legend>
		
				<fieldset class="clsFieldSet" style="margin: 4px 140px;">
					<legend class="LblFont">Selection</legend>
					
					<!-- ACH_BANK_ID DROP DOWN -->
					<label for="bankId" style="margin-left: 30px">ACH Bank Id</label>
					<select name="bankId" id="bankId" onChange="findBankInfo()">
						<option value=""></option>
						<% 
						if (bankIdVector != null && bankIdVector.size() > 0)
						{
							for (int i=0; i < bankIdVector.size(); i++)
							{
								Vector innerVector 	= (Vector) bankIdVector.elementAt(i);
								String bankId		= (String) innerVector.firstElement();				
						%>
						<option value="<%=bankId%>"><%=bankId%></option>	
						<% 		
							}
						}				
						%>				
					</select>												
				</fieldset>	
				
				<fieldset name="configDetails" id="configDetails" class="clsFieldSet">
					<legend class="LblFont">Settings</legend>
					
					<fieldset name="as2Details" id="as2Details" class="clsFieldSet">
						<legend class="LblFont">AS2</legend>
					
					
					
					
						<!-- PROTOCOL DROP DOWN -->
						<label for="protocol">Protocol</label>
						<select name="protocol" id="protocol">
							<option value=""></option>
							<option value="AS2">AS2</option>
							<option value="FTPS">FTPS</option>
							<option value="HTTPS">HTTPS</option>
						</select>
						
						<!-- URL -->
						<label for="sendUrl">Receipt URL</label>
						<input type="url" name="sendUrl" id="sendUrl" maxlength="100"/>	
							
						<!-- NACHA_AS2_SENDER_ID -->
						<label for="senderId">Sender AS2 Id</label>
						<input name="senderId" id="senderId" maxlength="100"/>	
									
						<!-- NACHA_AS2_RECEIVER_ID -->
						<label for="receiverId">Receiver AS2 Id</label>
						<input name="receiverId" id="receiverId" maxlength="100"/>
						
						 			
						<!-- NACHA_SENDER_ALIAS -->
						<TABLE style="text-align:center;" width="100%">
					  	<TR> 
					   	<TD style="width:615px;"><label style="width:285px;" for="senderAlias">Sender Partner Certificate</label><input name="senderAlias" id="senderAlias" maxlength="100" style="width:250px;"/></TD>
						<TD style="text-align:left;" ><label style="width:80x;" for="senderAliasExpDt">Exp Date</label>
						<input class="whereClause datePicker" name="senderCertExpDt" id="senderCertExpDt" maxlength="100" style="width:75px;"/></TD>
						</TR>
						</TABLE>
								
						<!-- NACHA_RECEIVER_ALIAS -->
						<TABLE style="text-align:center;" width="100%">
					  	<TR> 
					   	<TD style="width:615px;"><label style="width:285px;" for="receiverAlias">Receiver Partner Certificate</label><input name="receiverAlias" id=receiverAlias maxlength="100" style="width:250px;"/></TD>
						<TD style="text-align:left;" ><label style="width:80x;" for="receiverCertExpDt">Exp Date</label>
						<input class="whereClause datePicker" name="receiverCertExpDt" id="receiverCertExpDt" maxlength="100" style="width:75px;"/></TD>
						</TR>
						</TABLE>
						<br>					
					</fieldset>		
							
					<fieldset name="bankDetails" id="bankDetails" class="clsFieldSet">
						<legend class="LblFont">Bank</legend>
												
						<!-- NACHA_IMMEDIATE_DESTINATION -->
						<label for="immediateDestination">Immediate Destination</label>
						<input name="immediateDestination" id="immediateDestination" maxlength="10"/>
									
						<!-- NACHA_IMMEDIATE_ORIGIN -->
						<label for="immediateOrigin">Immediate Origin</label>
						<input name="immediateOrigin" id="immediateOrigin" maxlength="10"/>
									
						<!-- NACHA_IMMEDIATE_DEST_NAME -->
						<label for="immediateDestName">Immediate Destination Name</label>
						<input name="immediateDestName" id="immediateDestName" maxlength="23" style="width: 40%;"/>
										
						<!-- NACHA_IMMEDIATE_ORIG_NAME -->
						<label for="immediateOrigName">Immediate Origin Name</label>
						<input name="immediateOrigName" id="immediateOrigName" maxlength="23" style="width: 40%;"/>

						<!-- NACHA_SUPPORT_EMAIL -->
						<label for="supportEmail">Support Email</label>
						<input type="email" name="supportEmail" id="supportEmail" maxlength="100" style="width: 40%;"/>
						
						<!-- NACHA_SUPPORT_PHONE -->
						<label for="supportPhone">Support Phone Number</label>
						<input type="tel" name="supportPhone" id="supportPhone" maxlength="100" style="width: 40%;"/>

						<!-- FILE_PREFIX -->
						<label for="filePrefix">File Prefix</label>
						<input name="filePrefix" id="filePrefix" maxlength="25" style="width: 40%;"/>
																	
						<br><br>					
					</fieldset>
										
				</fieldset>
				
				<div id="settingsButton">
					<button name="btnUpdate" id="btnUpdate" class="clsLegButtonDisabled">Update</button>					
				</div>
				
				<div id="sendFileButton">
					<button name="btnSendFile" id="btnSendFile" class="clsLegButtonDisabled">Send Test File</button>
				</div>
				
				<div id="statusMessage">
					<p class="whiteFont">Status Message</p>
				</div>
				
			</fieldset>
		</form>
	</div>	
	
	<!-- Change Pop Up Hidden on Load -->     
	<DIV id="light" class="white_content2">	
		
		<h2 id="popUpTitle" style="margin-left:45px; margin-bottom:10px; margin-top:6px">Please Provide Password</h2>					
		
		<ul>
			<li>
				<label for="pw1" style="font-size:15px; font-family: Arial, Verdana, sans-serif;">Password</label>
				<input name="pw1" id="pw1" type="password" size="10" style="width:100px;"/>
			</li>
		</ul>	
						
		<A id="closeAnchor" style="margin-left:120px; font-size:20px; font-family: Arial, Verdana, sans-serif;" href="javascript:sendTestFile()" onclick="document.getElementById('light').style.display='none'; document.getElementById('fade').style.display='none'">Confirm</A>		  	
			
	</DIV>
	  	
	<DIV id="fade" class="black_overlay"></DIV>
	<!-- End Change Pop Up -->
<%
}
catch (Exception e)
{
	Logger.log("ACHBANKINFO", "ACHBankInfo.jsp --> EXCEPTION:", e.toString(), Logger.ERROR, Logger.LOG_COMMON);
	sException = URLEncoder.encode(e.toString());	
}

if (sException != null && sException.length() > 0)
{
%>				
	<script>	
		ShowServerError("<%=sException%>", "Web Logic");
	</script>	
<% 
}
else
{	
%>		
	<script>
	var fields = document.getElementById("configDetails").getElementsByTagName('*');
	
	for (var i = 0; i < fields.length; i++)
	{
	    fields[i].disabled = true;
	}	
	</script>
<% 
}
%>

</body>

</html>