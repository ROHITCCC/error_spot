package com.ultimo;

import io.undertow.server.HttpServerExchange;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.bson.types.ObjectId;
import org.json.JSONException;
import org.json.JSONObject;
import org.restheart.db.MongoDBClientSingleton;
import org.restheart.handlers.PipedHttpHandler;
import org.restheart.handlers.RequestContext;
import org.restheart.handlers.applicationlogic.ApplicationLogicHandler;
import org.restheart.security.handlers.IAuthToken;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;

public class BatchReplayService extends ApplicationLogicHandler implements IAuthToken {

	static MongoClient mongoClient= getMongoConnection();
	public BatchReplayService(PipedHttpHandler next, Map<String, Object> args) 
	{
		super(next, args);
	}

	@Override
	public void handleRequest(HttpServerExchange exchange, RequestContext context) throws Exception 
	
	{
		
		String payload= "";
		InputStream inputS = exchange.getInputStream();
		BufferedReader payloadReader = new BufferedReader(new InputStreamReader(inputS));
		while(true)
		{
			String input = payloadReader.readLine();
			if (input != null)
			{
				payload = payload + input;
			}
			else
			{
				break;//
			}
		}
		
		JSONObject input = new JSONObject(payload);
		BatchHandleRequest(input);

	}
	
	public static Map<String,String> BatchHandleRequest(JSONObject input) throws Exception
	{
		ArrayList<ObjectId> objectIDs = new ArrayList<ObjectId>();
		System.out.println("batch replay service:"+input.toString());
		String auditID = input.get("auditID").toString();
		auditID = auditID.replace("[", "").replace("]", "").replace("\"", "");
		String[] objectIDStrings = auditID.split(",");

		for (String id : objectIDStrings)
		{
			ObjectId object = new ObjectId(id);
			objectIDs.add(object);
		}
		
		JSONObject replayDestinationInfo = input.getJSONObject("replayDestinationInfo");
		System.out.println(replayDestinationInfo.get("type").toString());
		Map<String,String> result = null;
		if (replayDestinationInfo.get("type").toString().equalsIgnoreCase("REST"))
		{
			// Call Method Handling Rest Request
			result = handleRestBatch(input, objectIDs);
			
		}
		else if (replayDestinationInfo.get("type").toString().equalsIgnoreCase("WS"))
		{
			result = handleWSBatch(input, objectIDs);
		}
		else if (replayDestinationInfo.get("type").toString().equalsIgnoreCase("FILE"))
		{
			result = handleFileBatch(input, objectIDs);
		}
		else if (replayDestinationInfo.get("type").toString().equalsIgnoreCase("FTP"))
		{
			result = handleFTPBatch(input, objectIDs);
		}
		Iterator iterator = result.entrySet().iterator();
		while(iterator.hasNext())
		{
	        Map.Entry pair = (Map.Entry)iterator.next();
	        System.out.println("Key " + pair.getKey());
	        System.out.println("Value " + pair.getValue());
		}
		
		return result;
		
	}
	
	private static Map<String,String> handleRestBatch(JSONObject input, ArrayList<ObjectId> objectIDs) throws Exception
	{
		//Declare and Extract all Necessary Information
		JSONObject replayDestinationInfo = input.getJSONObject("replayDestinationInfo");
		String restMethod = replayDestinationInfo.getString("method");
		String restEndpoint = replayDestinationInfo.getString("endpoint");
		String contentType = replayDestinationInfo.getString("contentType");
		String restHeaders="";
		try{
		restHeaders = "[" + input.get("headers").toString().replace(":", "=").replace("{", "").replace("}", "") + "]";
		} catch(JSONException e){
			//if header don't exist we move on
		}
		
		String auditCollectionName = MongoDBClientSingleton.getErrorSpotConfig("u-audit-collection");
		String payloadCollectionName = MongoDBClientSingleton.getErrorSpotConfig("u-payload-collection");
		String mongoDatabase = MongoDBClientSingleton.getErrorSpotConfig("u-mongodb-database");
		Map<String,String> output = new HashMap<String,String>();
		
		//Create MongoDB Connection
		DB db = mongoClient.getDB(mongoDatabase);
		DBCollection auditCollection = db.getCollection(auditCollectionName);
		DBCollection payloadCollection = db.getCollection(payloadCollectionName);
		
		// Get DataLocations
		BasicDBList objectIds = new BasicDBList();
		objectIds.addAll(objectIDs);
		BasicDBObject auditSearchInClause = new BasicDBObject("$in",objectIds); 
		BasicDBObject auditSearchClause = new BasicDBObject("_id",auditSearchInClause); 

		DBCursor auditsResult = auditCollection.find(auditSearchClause);
		ArrayList<ObjectId> dataLocations = new ArrayList<ObjectId>();
		ArrayList<DBObject> auditList = new ArrayList<DBObject>();
		Map<String,String> payloadAndAuditId = new HashMap<String,String>();


		while (auditsResult.hasNext())
		{
			DBObject audit = auditsResult.next();
			if (audit.containsField("dataLocation"))
			{
				auditList.add(audit);
				String ObjectID = audit.get("dataLocation").toString();
				dataLocations.add(new ObjectId(ObjectID));
				payloadAndAuditId.put(ObjectID,audit.get("_id").toString());
				
			}

		}
		
		BasicDBList payloadIds = new BasicDBList();
		payloadIds.addAll(dataLocations);
		DBObject inClause = new BasicDBObject("$in",payloadIds);
		DBObject payloadQuery = new BasicDBObject("_id" , inClause);
		DBCursor payloadQueryResult = payloadCollection.find(payloadQuery);
		while (payloadQueryResult.hasNext())
		{
			DBObject payload = payloadQueryResult.next();
			String payloadID = payload.get("_id").toString();
			String convertedPayload = PayloadService.jsonToPayload(payload);
			String [] restRequestInput = new String[6];
			restRequestInput[0] = restEndpoint;
			restRequestInput[1] = restEndpoint;
			restRequestInput[2] = restMethod;
			restRequestInput[3] = contentType;
			restRequestInput[4] = convertedPayload;
			restRequestInput[5] = restHeaders;
			String auditID = payloadAndAuditId.get(payloadID);
			try
			{
			String[] handleResult = ReplayService.handleREST(restRequestInput);

			if (handleResult[1] !=null)
			{
			output.put(auditID, handleResult[1]);
			}
			}
			catch(Exception e)
			{
				output.put(auditID, "Undefined ErrorSpot Error");
				e.printStackTrace();
			}
		}
			
		return output;
		
	}
	
	private static Map<String,String> handleWSBatch(JSONObject input, ArrayList<ObjectId> objectIDs)
		{
		//Declare and Extract all Necessary Information
		String auditCollectionName = MongoDBClientSingleton.getErrorSpotConfig("u-audit-collection");
		String payloadCollectionName = MongoDBClientSingleton.getErrorSpotConfig("u-payload-collection");
		String mongoDatabase = MongoDBClientSingleton.getErrorSpotConfig("u-mongodb-database");
		JSONObject replayDestinationInfo = input.getJSONObject("replayDestinationInfo");
		String wsSoapAction= replayDestinationInfo.getString("soapaction");
		String wsdl = replayDestinationInfo.getString("wsdl");
		String wsBinding = replayDestinationInfo.getString("binding");
		String wsOperation = replayDestinationInfo.getString("operation");
		Map<String,String> output = new HashMap<String,String>();
		Map<String,String> payloadAndAuditId = new HashMap<String,String>();

		
		
		
		
		//Create MongoDB Connection
		DB db = mongoClient.getDB(mongoDatabase);
		DBCollection auditCollection = db.getCollection(auditCollectionName);
		DBCollection payloadCollection = db.getCollection(payloadCollectionName);
		
		// Get DataLocations
		BasicDBList objectIds = new BasicDBList();
		objectIds.addAll(objectIDs);
		BasicDBObject auditSearchInClause = new BasicDBObject("$in",objectIds); 
		BasicDBObject auditSearchClause = new BasicDBObject("_id",auditSearchInClause); 

		DBCursor auditsResult = auditCollection.find(auditSearchClause);
		ArrayList<ObjectId> dataLocations = new ArrayList<ObjectId>();

		while (auditsResult.hasNext())
		{
			DBObject audit = auditsResult.next();
			if (audit.containsField("dataLocation"))
			{
				String ObjectID = audit.get("dataLocation").toString();
				System.out.println(ObjectID);
				dataLocations.add(new ObjectId(ObjectID));
				payloadAndAuditId.put(ObjectID,audit.get("_id").toString());

			}

		}
		
		BasicDBList payloadIds = new BasicDBList();
		payloadIds.addAll(dataLocations);
		DBObject inClause = new BasicDBObject("$in",payloadIds);
		DBObject payloadQuery = new BasicDBObject("_id" , inClause);
		DBCursor payloadQueryResult = payloadCollection.find(payloadQuery);
		while (payloadQueryResult.hasNext())
		{
			DBObject payload = payloadQueryResult.next();
			String payloadID = payload.get("_id").toString();
			String auditID = payloadAndAuditId.get(payloadID);

			String convertedPayload = PayloadService.jsonToPayload(payload);
			System.out.println(convertedPayload);
			String [] wsRequestInput = new String[6];
			wsRequestInput[0] = "";
			wsRequestInput[1] = wsdl;
			wsRequestInput[2] = wsOperation;
			wsRequestInput[3] = wsSoapAction;
			wsRequestInput[4] = wsBinding;
			wsRequestInput[5] = convertedPayload;

			try 
			{
				String[] handleResult = ReplayService.handleWS(wsRequestInput);
				if (handleResult[1] !=null)
				{
				output.put(auditID, handleResult[1]);
				}
				}
				catch(Exception e)
				{
					if (e.getMessage() != null)
					{
						output.put(auditID, e.getMessage());

					}
					else
					{
					output.put(auditID, "Undefined ErrorSpot Error");
					e.printStackTrace();
					}
				}					}
		return output;
		
	}
	
	private static Map<String,String> handleFileBatch(JSONObject input, ArrayList<ObjectId> objectIDs)
	{
		//Declare and Extract all Necessary Information
		JSONObject replayDestinationInfo = input.getJSONObject("replayDestinationInfo");
		String fileLocation = replayDestinationInfo.getString("fileLocation");
		System.out.println(fileLocation);
		String fileName =fileLocation.split("\\.")[0];
		String fileType = fileLocation.split("\\.")[1];
		System.out.println(fileLocation + "BYEAH");
		String auditCollectionName = MongoDBClientSingleton.getErrorSpotConfig("u-audit-collection");
		String payloadCollectionName = MongoDBClientSingleton.getErrorSpotConfig("u-payload-collection");
		String mongoDatabase = MongoDBClientSingleton.getErrorSpotConfig("u-mongodb-database");
		Map<String,String> output = new HashMap<String,String>();
		
		
		//Create MongoDB Connection
		DB db = mongoClient.getDB(mongoDatabase);
		DBCollection auditCollection = db.getCollection(auditCollectionName);
		DBCollection payloadCollection = db.getCollection(payloadCollectionName);
		
		// Get DataLocations
		BasicDBList objectIds = new BasicDBList();
		objectIds.addAll(objectIDs);
		BasicDBObject auditSearchInClause = new BasicDBObject("$in",objectIds); 
		BasicDBObject auditSearchClause = new BasicDBObject("_id",auditSearchInClause); 
		Map<String,String> payloadAndAuditId = new HashMap<String,String>();

		DBCursor auditsResult = auditCollection.find(auditSearchClause);
		ArrayList<ObjectId> dataLocations = new ArrayList<ObjectId>();

		while (auditsResult.hasNext())
		{
			DBObject audit = auditsResult.next();
			if (audit.containsField("dataLocation"))
			{
				String ObjectID = audit.get("dataLocation").toString();
				dataLocations.add(new ObjectId(ObjectID));
				payloadAndAuditId.put(ObjectID,audit.get("_id").toString());
				
			}

		}
		
		BasicDBList payloadIds = new BasicDBList();
		payloadIds.addAll(dataLocations);
		DBObject inClause = new BasicDBObject("$in",payloadIds);
		DBObject payloadQuery = new BasicDBObject("_id" , inClause);
		DBCursor payloadQueryResult = payloadCollection.find(payloadQuery);
		while (payloadQueryResult.hasNext())
		{
			DBObject payload = payloadQueryResult.next();
			String payloadID = payload.get("_id").toString();
			String auditID = payloadAndAuditId.get(payloadID);

			String id = payload.get("_id").toString();
			System.out.println(payload.toString());
			String convertedPayload = PayloadService.jsonToPayload(payload);
			String [] fileInput = new String[3];
			Calendar cal = Calendar.getInstance();
			DateFormat dateFormat = new SimpleDateFormat("MM_dd_yyyy_HH_mm_ss_");
			String sysDate = dateFormat.format(cal.getTime());
			// Payload ID to Track them. 
			fileInput[1] = fileName +"_"+ sysDate + id +  "."+ fileType;
			fileInput[2] = convertedPayload;
			try {
				String[] handleResult = ReplayService.handleFILE(fileInput);
				if (handleResult[1] !=null)
				{
				output.put(auditID, handleResult[1]);
				}
				}
				catch(Exception e)
				{
					if (e.getMessage() != null)
					{
						output.put(auditID, e.getMessage());

					}
					else
					{
					output.put(auditID, "Undefined ErrorSpot Error");
					e.printStackTrace();
					}
				}			
		}
		return output;
		
	}

	private static Map<String,String> handleFTPBatch(JSONObject input, ArrayList<ObjectId> objectIDs)
	{
		//Declare and Extract all Necessary Information
		
		JSONObject replayDestinationInfo = input.getJSONObject("replayDestinationInfo");
		String location = replayDestinationInfo.getString("location");
		String hostname = replayDestinationInfo.getString("hostname");
		String username = replayDestinationInfo.getString("username");
		String password = replayDestinationInfo.getString("password");
		String fileType = replayDestinationInfo.getString("filetype");
		String fileName = replayDestinationInfo.getString("filename");
		String auditCollectionName = MongoDBClientSingleton.getErrorSpotConfig("u-audit-collection");
		String payloadCollectionName = MongoDBClientSingleton.getErrorSpotConfig("u-payload-collection");
		String mongoDatabase = MongoDBClientSingleton.getErrorSpotConfig("u-mongodb-database");
		Map<String,String> output = new HashMap<String,String>();

		
		//Create MongoDB Connection
		DB db = mongoClient.getDB(mongoDatabase);
		DBCollection auditCollection = db.getCollection(auditCollectionName);
		DBCollection payloadCollection = db.getCollection(payloadCollectionName);
		Map<String,String> payloadAndAuditId = new HashMap<String,String>();

		// Get DataLocations
		BasicDBList objectIds = new BasicDBList();
		objectIds.addAll(objectIDs);
		BasicDBObject auditSearchInClause = new BasicDBObject("$in",objectIds); 
		BasicDBObject auditSearchClause = new BasicDBObject("_id",auditSearchInClause); 

		DBCursor auditsResult = auditCollection.find(auditSearchClause);
		ArrayList<ObjectId> dataLocations = new ArrayList<ObjectId>();

		while (auditsResult.hasNext())
		{
			DBObject audit = auditsResult.next();
			if (audit.containsField("dataLocation"))
			{
				String ObjectID = audit.get("dataLocation").toString();
				//System.out.println(ObjectID);
				dataLocations.add(new ObjectId(ObjectID));
				payloadAndAuditId.put(ObjectID,audit.get("_id").toString());

			}

		}
		
		BasicDBList payloadIds = new BasicDBList();
		payloadIds.addAll(dataLocations);
		DBObject inClause = new BasicDBObject("$in",payloadIds);
		DBObject payloadQuery = new BasicDBObject("_id" , inClause);
		DBCursor payloadQueryResult = payloadCollection.find(payloadQuery);
		while (payloadQueryResult.hasNext())
		{
			DBObject payload = payloadQueryResult.next();
			String payloadID = payload.get("_id").toString();
			String auditID = payloadAndAuditId.get(payloadID);

			String id = payload.get("_id").toString();
			System.out.println(payload.toString());
			String convertedPayload = PayloadService.jsonToPayload(payload);
			String [] FTPInput = new String[9];
			Calendar cal = Calendar.getInstance();
			DateFormat dateFormat = new SimpleDateFormat("MM_dd_yyyy_HH_mm_ss_");
			String sysDate = dateFormat.format(cal.getTime());
			// Payload ID to Track them. 

			
			FTPInput[1] = hostname;
			FTPInput[2] = username;
			FTPInput[3] = password;
			FTPInput[4] = location;
			FTPInput[5] = fileName +"_"+ sysDate + id;
			FTPInput[6] = fileType;
			FTPInput[7] = convertedPayload;
			FTPInput[8] = "";


			
			System.out.println(FTPInput[1]);
			try {
				String[] handleResult = ReplayService.handleFTP(FTPInput);				
				if (handleResult[1] !=null)
				{
				output.put(auditID, handleResult[1]);
				}
				}
				catch(Exception e)
				{
					if (e.getMessage() != null)
					{
						output.put(auditID, e.getMessage());

					}
					else
					{
					output.put(auditID, "Undefined ErrorSpot Error");
					e.printStackTrace();
					}
				}	
		}
		return output;
		
	}
	
	private static MongoClient getMongoConnection() {
		MongoClient client = MongoDBClientSingleton.getInstance().getClient();   
		return client;
			}



}
