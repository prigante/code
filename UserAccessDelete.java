package com.vno.UserRename;

import java.util.Vector;

import com.ibs.common.db.DbInterface;
import com.ibs.common.logger.appLogger;

public class UserAccessDelete 
{
	/**	
	 * 1. Get all Not Active Users from USER_PROFILE
	 * 2. For each user in set update all rows in USER_SYSTEM to ACTIVE = N, MODIFY_TSTAMP = SYSDATE
	 * 3. Query for all USER_SYSTEM users not active since last run time 
	 * 4. For each user in #3 delete TBLACCS & USER_GROUP records
	 * 
	 * @param rsObj
	 * @param commSchema
	 * @return true
	 */
	public static boolean deleteUserAccess(DbInterface rsObj, String commSchema)
	{
		try 
		{
			appLogger.logMssg(appLogger.LOG_LEV_INFO, "Start UserAccessDelete.deleteUserAccess");
						
			// Get the last time the utilty ran date
			String lastDeleteDate = getLastDeleteDate(rsObj, commSchema);
			appLogger.logMssg(appLogger.LOG_LEV_INFO, "lastDeleteDate " + lastDeleteDate);
			
			// Get all Not Active Users from EPMCOMM.USER_PROFILE
			String notActiveUsersSql = "SELECT USER_ID FROM " + commSchema + ".USER_PROFILE WHERE ACTIVE_FLAG = 'N' AND MODIFY_TSTAMP > TO_DATE('" + lastDeleteDate + "', 'MM/DD/YYYY')";
		
			Vector notActiveUsersVector = rsObj.getResultVector(notActiveUsersSql);
						
			// For each user in set update all rows in EPMCOMM.USER_SYSTEM to ACTIVE = N, MODIFY_TSTAMP = SYSDATE
			if (notActiveUsersVector != null && notActiveUsersVector.size() > 0)
			{				
				for (int i = 0; i < notActiveUsersVector.size(); i++)
				{
					Vector userIdVector = (Vector) notActiveUsersVector.elementAt(i);
					String userId = (String) userIdVector.firstElement();
					
					String userSystemSql = "UPDATE " + commSchema + ".USER_SYSTEM SET ACTIVE_FLAG = 'N', MODIFY_TSTAMP = SYSDATE WHERE USER_ID = '" + userId + "'";
					
					try
					{
						rsObj.executeUpdate(userSystemSql);
					}
					catch (Exception e)
					{
						appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Update to User System table failed, moving on...");
					}
				}
			}
			
			// Query for all EPMCOMM.USER_SYSTEM users not active since last run time
			StringBuffer notActiveUserSystemSql = new StringBuffer();
			
			notActiveUserSystemSql.append("SELECT US.USER_ID, US.IBS_USER, DB.DB_SCHEMA ");		
			notActiveUserSystemSql.append("FROM " + commSchema + ".USER_SYSTEM US, " + commSchema + ".SYSTEMS SYS, " + commSchema + ".DB_INSTANCE DB ");
			notActiveUserSystemSql.append("WHERE US.ACTIVE_FLAG = 'N' ");
			notActiveUserSystemSql.append("AND US.MODIFY_TSTAMP > TO_DATE('" + lastDeleteDate + "', 'MM/DD/YYYY') ");			
			notActiveUserSystemSql.append("AND US.CUST_ID = SYS.CUST_ID ");
			notActiveUserSystemSql.append("AND US.SYSTEM_ID = SYS.SYSTEM_ID ");
			notActiveUserSystemSql.append("AND US.SYSTEM_ID = SYS.SYSTEM_ID ");
			notActiveUserSystemSql.append("AND SYS.DB_INST_ID = DB.DB_INST_ID ");
			
			Vector userAccessDeleteVector = rsObj.getResultVector(notActiveUserSystemSql.toString());
			
			// For each user in set delete TBLACCS & USER_GROUP records
			if (userAccessDeleteVector != null && userAccessDeleteVector.size() > 0)
			{				
				for (int i = 0; i < userAccessDeleteVector.size(); i++)
				{			
					Vector innerVector = (Vector) userAccessDeleteVector.elementAt(i);
					
					String userId = (String) innerVector.elementAt(0);					
					String blueId = (String) innerVector.elementAt(1);
					String schema = (String) innerVector.elementAt(2);					
					
					// Delete from User Access in each applicable schema 
					String userAccessdeleteSql = "DELETE FROM " + schema + ".USER_ACCESS WHERE USER_ID = '" + userId + "'";
					
					try
					{
						rsObj.executeUpdate(userAccessdeleteSql);
					}
					catch (Exception e)
					{
						appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Delete from User Access table failed, moving on...");
					}
					
					// Delete from TBLACS1 in each applicable schema
					String acs1DeleteSql = "DELETE FROM " + schema + ".TBLACS1 WHERE IBS_USER_ID = '" + blueId + "'";
					
					try
					{						
						rsObj.executeUpdate(acs1DeleteSql);
					}
					catch (Exception e)
					{
						appLogger.logMssg(appLogger.LOG_LEV_ERROR, "Delete from TBLACS1 table failed, moving on...");
					}								
				}	
			}			
		} 
		catch (Exception e) 
		{			
			e.printStackTrace();
			appLogger.logMssg(appLogger.LOG_LEV_INFO, "UserAccessDelete.deleteUserAccess Exception = " + e.toString());
		}
	
		return true;
	}

	// Need to get the last delete date from the System Control table in EPMCOMM
	// Adding in an additonal day
	private static String getLastDeleteDate(DbInterface rsObj, String commSchema) throws Exception 
	{
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "Start UserAccessDelete.getLastDeleteDate");
		
		// Get Last User Access Delete Date from EPMCOMM System Control 
		String sql = "SELECT NVL(TO_CHAR(SEQ_DATE1-1, 'MM/DD/YYYY'), '1/1/2000') FROM " + commSchema + ".SYSTEM_CONTROL WHERE CNTL_KEY1 = 'LAST_UAC_DELETE'";
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "getLastDeleteDate sql = " + sql);	
		
		Vector lastDeleteDateVector = rsObj.getResultVector(sql);	
		Vector innerVector = (Vector) lastDeleteDateVector.firstElement();
		String date = (String) innerVector.firstElement();
				
		// Update Last User Access Delete Date in EPMCOMM System Control 
		String updateSql = "UPDATE " + commSchema + ".SYSTEM_CONTROL SET SEQ_DATE1 = SYSDATE WHERE CNTL_KEY1 = 'LAST_UAC_DELETE'";
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "getLastDeleteDate sql = " + sql);			

		int numberOfRowsUpdated = rsObj.executeUpdate(updateSql);
		appLogger.logMssg(appLogger.LOG_LEV_INFO, "getLastDeleteDate numberOfRowsUpdated = " + numberOfRowsUpdated);
		
		return date;
	}	
}