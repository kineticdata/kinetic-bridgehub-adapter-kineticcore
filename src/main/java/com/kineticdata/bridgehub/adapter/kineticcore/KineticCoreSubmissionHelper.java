package com.kineticdata.bridgehub.adapter.kineticcore;

import com.kineticdata.bridgehub.adapter.BridgeError;
import com.kineticdata.bridgehub.adapter.BridgeRequest;
import com.kineticdata.bridgehub.adapter.BridgeUtils;
import com.kineticdata.bridgehub.adapter.Count;
import com.kineticdata.bridgehub.adapter.Record;
import com.kineticdata.bridgehub.adapter.RecordList;
import static com.kineticdata.bridgehub.adapter.kineticcore.KineticCoreAdapter.logger;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

/**
 *
 */
public class KineticCoreSubmissionHelper {
    private final String username;
    private final String password;
    private final String spaceUrl;
    private final Pattern fieldPattern;
    
    public KineticCoreSubmissionHelper(String username, String password, String spaceUrl) {
        this.username = username;
        this.password = password;
        this.spaceUrl = spaceUrl;
        this.fieldPattern = Pattern.compile("(\\S+)\\[(.*?)\\]");
    }
    
    public Count count(BridgeRequest request) throws BridgeError {
       Integer count = countSubmissions(request,null);

        return new Count(count);
    }
    
    public Record retrieve(BridgeRequest request) throws BridgeError {
        String submissionId = null;
        if (request.getQuery().matches("[Ii][Dd]=.*?(?:$|&)")) {
            Pattern p = Pattern.compile("[Ii][Dd]=(.*?)(?:$|&)");
            Matcher m = p.matcher(request.getQuery());
        
            if (m.find()) {
                submissionId = m.group(1);
            }
        }

        String url;
        JSONObject submission;
        if (submissionId == null) {
            JSONObject response = searchSubmissions(request);
            JSONArray submissions = (JSONArray)response.get("submissions");

            if (submissions.size() > 1) {
                throw new BridgeError("Multiple results matched an expected single match query");
            } else if (submissions.isEmpty()) {
                submission = null;
            } else {
                submission = (JSONObject)submissions.get(0);
            }
        } else {
            url = String.format("%s/app/api/v1/submissions/%s?include=values,details",this.spaceUrl,submissionId);

            HttpClient client = new DefaultHttpClient();
            HttpResponse response;
            HttpGet get = new HttpGet(url);
            get = addAuthenticationHeader(get, this.username, this.password);

            String output = "";
            try {
                response = client.execute(get);

                logger.trace("Request response code: " + response.getStatusLine().getStatusCode());
                HttpEntity entity = response.getEntity();
                if (response.getStatusLine().getStatusCode() == 404) {
                    throw new BridgeError(String.format("Not Found: The submission with the id '%s' cannot be found.",submissionId));
                }
                output = EntityUtils.toString(entity);
            } 
            catch (IOException e) {
                logger.error(e.getMessage());
                throw new BridgeError("Unable to make a connection to the Kinetic Core server."); 
            }

            JSONObject json = (JSONObject)JSONValue.parse(output);
            submission = (JSONObject)json.get("submission");
        }
        
        return createRecordFromSubmission(request.getFields(), submission);
    }
    
    public RecordList search(BridgeRequest request) throws BridgeError {
        Map<String,String> metadata = BridgeUtils.normalizePaginationMetadata(request.getMetadata());
        
        JSONObject response = searchSubmissions(request);
        JSONArray submissions = (JSONArray)response.get("submissions");
        
        List<Record> records = createRecordsFromSubmissions(request.getFields(), submissions);
        
        metadata.put("size", String.valueOf(submissions.size()));
        metadata.put("nextPageToken",(String)response.get("nextPageToken"));

        // Return the response
        return new RecordList(request.getFields(), records, metadata);
    }
    
    /*---------------------------------------------------------------------------------------------
     * HELPER METHODS
     *-------------------------------------------------------------------------------------------*/
    
    private HttpGet addAuthenticationHeader(HttpGet get, String username, String password) {
        String creds = username + ":" + password;
        byte[] basicAuthBytes = Base64.encodeBase64(creds.getBytes());
        get.setHeader("Authorization", "Basic " + new String(basicAuthBytes));

        return get;
    }

    // A helper method used to call createRecordsFromSubmissions but with a 
    // single record instead of an array
    private Record createRecordFromSubmission(List<String> fields, JSONObject submission) throws BridgeError {
        Record record;
        if (submission != null) {
            JSONArray jsonArray = new JSONArray();
            jsonArray.add(submission);
            record = createRecordsFromSubmissions(fields,jsonArray).get(0);
        } else {
            record = new Record();
        }
        return record;
    }

    private List<Record> createRecordsFromSubmissions(List<String> fields, JSONArray submissions) throws BridgeError {
        // Create 'searchable' field list. If there needs to be a multi-level
        // search (aka, values[Group]) the field's type will be List<String>
        // instead of just String
        List searchableFields = new ArrayList();
        for (String field : fields) {
            Matcher matcher = fieldPattern.matcher(field);
            if (matcher.find()) {
                List<String> multiLevelField = new ArrayList<>();
                multiLevelField.add(matcher.group(1));
                multiLevelField.add(matcher.group(2));
                searchableFields.add(multiLevelField);
            } else {
                searchableFields.add(field);
            }
        }

        // Go through the submissions in the JSONArray to create a list of records
        List<Record> records = new ArrayList<>();
        for (Object o : submissions) {
            JSONObject submission = (JSONObject)o;
            Map<String,Object> record = new LinkedHashMap<>();
            for (int fldIndex=0;fldIndex<fields.size();fldIndex++) {
                Object searchableField = searchableFields.get(fldIndex);
                if (searchableField.getClass() == String.class) {
                    // If the field is a string, just do a simple retrieve and put
                    record.put(fields.get(fldIndex),submission.get(searchableField.toString()));
                } else if (searchableField.getClass() == ArrayList.class) {
                    // If the field is a list, iterate through the object until you
                    // find the result
                    List<String> multiLevelField = (ArrayList<String>)searchableField;
                    JSONObject jsonObject = (JSONObject)submission.get(multiLevelField.get(0));
                    record.put(fields.get(fldIndex),jsonObject.get(multiLevelField.get(1)));
                } else {
                    throw new BridgeError("There was an error with parsing the record object. Field type '"+searchableField.getClass()+"' is not valid.");
                }
            }
            records.add(new Record(record));
        }

        return records;
    }
    
    private Integer countSubmissions(BridgeRequest request, String pageToken) throws BridgeError {
        Integer count = 0;
        String[] indvQueryParts = request.getQuery().split("&");

        // Retrieving the slugs for the kapp and form slug that were passed in the query
        String kappSlug = null;
        String formSlug = null;
        List<String> queryPartsList = new ArrayList<>();
        for (String indvQueryPart : indvQueryParts) {
            String[] str_array = indvQueryPart.split("=");
            String field = str_array[0].trim();
            String value = "";
            if (str_array.length > 1) value = str_array[1].trim();
            if (field.equals("formSlug")) { formSlug = value; }
            else if (field.equals("kappSlug")) { kappSlug = value; }
            else if (!field.equals("limit")) { // ignore the limit, because count always uses the default limit
                queryPartsList.add(URLEncoder.encode(field) + "=" + URLEncoder.encode(value));
            }
        }
        queryPartsList.add("limit=200");
        String query = StringUtils.join(queryPartsList,"&");
        
        if (kappSlug == null) {
            throw new BridgeError("Invalid Request: The bridge query needs to include a kappSlug.");
        }
        
        
        // Make sure that the pageToken isn't null for the first pass.
        String nextToken = pageToken != null ? pageToken : "";
        while (nextToken != null) {
            // the token query is used to reset the query each time so that multiple pageTokens 
            // aren't added to the query after multiple passes
            String tokenQuery = query;
            // if nextToken is empty, don't add to query (only relevant on first pass)
            if (!nextToken.isEmpty()) {
                tokenQuery = tokenQuery+"&pageToken="+nextToken;
            }
            JSONObject json = searchSubmissions(kappSlug, formSlug, tokenQuery);
            nextToken = (String)json.get("nextPageToken");
            JSONArray submissions = (JSONArray)json.get("submissions");
            count += submissions.size();
        }
        
        return count;
    }
    
    private JSONObject searchSubmissions(BridgeRequest request) throws BridgeError {
        String[] indvQueryParts = request.getQuery().split("&");

        // Retrieving the slugs for the kapp and form slug that were passed in the query
        String kappSlug = null;
        String formSlug = null;
        String limit = null;
        List<String> queryPartsList = new ArrayList<>();
        for (String indvQueryPart : indvQueryParts) {
            String[] str_array = indvQueryPart.split("=");
            String field = str_array[0].trim();
            String value = "";
            if (str_array.length > 1) value = str_array[1].trim();
            if (field.equals("formSlug")) { formSlug = value; }
            else if (field.equals("kappSlug")) { kappSlug = value; }
            else if (field.equals("limit")) { limit = value; }
            else {
                queryPartsList.add(URLEncoder.encode(field) + "=" + URLEncoder.encode(value));
            }
        }
        
        // Add a limit to the query by either using the value that was passed, or defaulting limit=200
        // Also add the include statement to get extra values and details
        if (limit != null && !limit.isEmpty()) {
            queryPartsList.add("include=values,details&limit="+limit);
        } else {
            queryPartsList.add("include=values,details&limit=200");
        }
        
        // If metadata[nextPageToken] is included in the request, add it to the query
        if (request.getMetadata("pageToken") != null) {
            queryPartsList.add("pageToken="+request.getMetadata("pageToken"));
        }
        
        // Join the query list into a query string
        String query = StringUtils.join(queryPartsList,"&");
        
        if (kappSlug == null) {
            throw new BridgeError("Invalid Request: The bridge query needs to include a kappSlug.");
        }
        
        return searchSubmissions(kappSlug, formSlug, query);
    }
    
    private JSONObject searchSubmissions(String kapp, String form, String query) throws BridgeError {
        // Initializing the Http Objects
        HttpClient client = new DefaultHttpClient();
        HttpResponse response;
        
        // Build the submissions api url. Url is different based on whether the form slug has been included.
        String url;
        if (form != null) {
            url = String.format("%s/app/api/v1/kapps/%s/forms/%s/submissions?%s",this.spaceUrl,kapp,form,query);
        } else {
            url = String.format("%s/app/api/v1/kapps/%s/submissions?%s",this.spaceUrl,kapp,query);
        }
        HttpGet get = new HttpGet(url);
        get = addAuthenticationHeader(get, this.username, this.password);
        
        String output = "";
        try {
            response = client.execute(get);
            
            HttpEntity entity = response.getEntity();
            output = EntityUtils.toString(entity);
            logger.trace("Request response code: " + response.getStatusLine().getStatusCode());
        }
        catch (IOException e) {
            logger.error(e.getMessage());
            throw new BridgeError("Unable to make a connection to the Kinetic Core server."); 
        }
        
        logger.trace("Starting to parse the JSON Response");
        JSONObject json = (JSONObject)JSONValue.parse(output);
        
        if (response.getStatusLine().getStatusCode() == 404) {
            throw new BridgeError("Invalid kappSlug or formSlug: " + json.get("error").toString());
        } else if (response.getStatusLine().getStatusCode() != 200) {
            String errorMessage = json.containsKey("error") ? json.get("error").toString() : json.toJSONString();
            throw new BridgeError("Bridge Error: " + errorMessage);
        }

        JSONArray messages = (JSONArray)json.get("messages");

        if (!messages.isEmpty()) {
            logger.trace("Message from the Submissions API for the follwing query: "+query+"\n"+StringUtils.join(messages,"; "));
        }

        return json;
    }
}
