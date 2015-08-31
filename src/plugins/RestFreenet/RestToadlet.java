package plugins.RestFreenet;

import freenet.client.ClientMetadata;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientPutCallback;
import freenet.client.async.ClientPutter;
import freenet.clients.fcp.FCPPluginConnection;
import freenet.clients.fcp.FCPPluginMessage;
import freenet.clients.http.LinkEnabledCallback;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.node.Node;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ClientSideFCPMessageHandler;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import org.json.simple.JSONObject;
import freenet.support.api.RandomAccessBucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ResumeFailedException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * The Toadlet class
 * This class actually handles the incoming HTTP requests 
 * 
 * @author Konstantinos Togias <info@ktogias.gr>
 */
public class RestToadlet extends Toadlet implements LinkEnabledCallback{
    protected String path; //The url path under witch the Toadlet is accessed
    protected PluginRespirator pr;
    protected String indynetPluginName;
    protected HighLevelSimpleClient client;
    protected Node node;

    /**
     * Class Constructor
     * 
     * @param path String : The url path under witch the Toadlet is accessed
     * @param indynetPluginName String : The name of indynet plugin
     * @param pr PluginRespirator : The plugin respirator
     *
     */
    public RestToadlet(String path, String indynetPluginName, PluginRespirator pr) {
        super(pr.getHLSimpleClient());
        this.path = path;
        this.pr = pr;
        this.indynetPluginName = indynetPluginName;
        this.client = pr.getHLSimpleClient();
        this.node = pr.getNode();
    }
    
    /**
     * Resturns the path
     * 
     * @return String 
     */
    @Override
    public String path() {
            return path;
    }

    /**
     * Implementation of handleMethodGET
     * This method handles HTTP GET requests
     * 
     * @param uri URI : The URI of the request
     * @param httpr HTTPRequest : The request object
     * @param tc ToadletContext : The Context object
     * @throws ToadletContextClosedException
     * @throws IOException
     * @throws RedirectException 
     */
    @Override
    public void handleMethodGET(URI uri, HTTPRequest httpr, ToadletContext tc) throws ToadletContextClosedException, IOException, RedirectException {
        String action;
        try {
            action = getActionFromUri(uri);
        }
        catch (Exception ex) {
            writeReply(tc, 400, "text/plain", "error", "Bad request");
            return;
        }
        try {
            if (action.equalsIgnoreCase("keygen")){
                handleKeygen(uri, httpr, tc);
            }
            else if (action.equalsIgnoreCase("resname")){
                handleResname(uri, httpr, tc);
            }
            else {
                writeReply(tc, 405, "text/plain", "error", "Requested action is not supported");
            }
        } catch (Exception e){ 
            writeReply(tc, 500, "text/plain", "error", "Server error: "+e.toString());
        }  
    }
    
    /**
     * handleMethodPOST method handles HTTP POST requests
     * 
     * @param uri URI : The URI of the request
     * @param httpr HTTPRequest : The request object
     * @param tc ToadletContext : The Context object
     * @throws ToadletContextClosedException
     * @throws IOException
     * @throws RedirectException 
     */
    public void handleMethodPOST(URI uri, HTTPRequest httpr, ToadletContext tc) throws ToadletContextClosedException, IOException, RedirectException{
        String action;
        try {
            action = getActionFromUri(uri);
        } catch (Exception ex) {
            writeReply(tc, 400, "text/plain", "error", "Bad request");
            return;
        }
        try {
            if (action.equalsIgnoreCase("insert")){
                handleInsert(uri, httpr, tc);
            }
            else if (action.equalsIgnoreCase("regname")){
                handleRegName(uri, httpr, tc);
            }
            else {
                writeReply(tc, 405, "text/plain", "error", "Requested action is not supported");
            }
        } catch (Exception e){
            writeReply(tc, 500, "text/plain", "error", "Server error: "+e.toString());
        }
    }
    
    /**
     * handleInsert method gets the relevant parameters, 
     * calls the method that does the insert 
     * and sends the reply for requests to insert data.
     * 
     * @param uri URI : The URI of the request 
     * @param httpr HTTPRequest : The request object
     * @param tc ToadletContext : The Context object
     * @throws Exception 
     */
    public void handleInsert(URI uri, HTTPRequest httpr, ToadletContext tc) throws Exception{
            String contenttype = httpr.getHeader("Content-Type");
            Map<String,String> params = getInsertParamsFromUri(uri);
            String key = params.get("key");
            String filename = params.get("filename");
            Bucket data = httpr.getRawData();
            data.setReadOnly();
            String priorityParam = httpr.getParam("priority");
            String realtimeParam = httpr.getParam("realtime");
            short priority = RequestStarter.INTERACTIVE_PRIORITY_CLASS;
            boolean realtime = false;
            if (realtimeParam.equals("1") || realtimeParam.equalsIgnoreCase("true") || realtimeParam.equalsIgnoreCase("yes")){
                realtime = true;
            }
            if (!priorityParam.isEmpty()){
                priority = Short.parseShort(priorityParam);
            }

            InsertStatusCallback callback = insert(key, filename, contenttype, data, priority, realtime, tc);
            int status = callback.getStatus();
            if (status == InsertStatusCallback.STATUS_SUCCESS){
                /*Create the json object with the URI pair to return*/
                JSONObject response = new JSONObject();
                response.put("requestURI", callback.getInsertedURI().toString());
                /*Send the reply*/
                writeReply(tc, 200, "application/json", "", response.toJSONString());
            }
            else if (status == InsertStatusCallback.STATUS_FAILURE){
                /*Send the reply*/
                writeReply(tc, 500, "text/plain", "", "Insert failed "+callback.getInsertException().toString());
            }
            else if (status == InsertStatusCallback.STATUS_CANCELLED ){
                /*Send the reply*/
                writeReply(tc, 500, "text/plain", "", "Insert was cancelled");
            }
    }
    
    /**
     * handleKeygen method gets the relevant parameters, 
     * calls the method that does the key generation 
     * and sends the reply for requests to generate a key pair. 
     * 
     * @param uri URI : The URI of the request 
     * @param httpr HTTPRequest : The request object 
     * @param tc ToadletContext : The Context object
     * @throws Exception 
     */
    public void handleKeygen(URI uri, HTTPRequest httpr, ToadletContext tc) throws Exception{
        try {
            String insertUri;
            String requestUri;
            
            Map<String,String> params = getKeygenParamsFromUri(uri);
            String keytype = params.get("keytype");
            String filename = httpr.getParam("filename");
            if (filename.isEmpty()){
                filename = "";
            }
            
            int version = -1;
            String versionString = httpr.getParam("version");
            if (!versionString.isEmpty()){
                version= Integer.parseUnsignedInt(versionString);
            }
            
            if (keytype.equalsIgnoreCase("SSK")){
                InsertableClientSSK key = keygenSSK(filename, version);
                insertUri = key.getInsertURI().toString();
                requestUri = key.getURI().toString();
            }
            else if (keytype.equalsIgnoreCase("USK")){
                /*For USK we create an SSK and transorm insertURI and requestURI to USK*/
                InsertableClientSSK key = keygenSSK(filename);
                insertUri = key.getInsertURI().toString().replace("SSK","USK");
                requestUri = key.getURI().toString().replace("SSK","USK");
                if (!filename.equals("")){
                    /*If no filename is provided we ignore version*/
                    if (version < 0){
                        insertUri+="/0";
                        requestUri+="/-1";
                    }
                    else {
                        insertUri+="/"+version;
                        requestUri+="/"+version;
                    }
                }
            }
            else {
                writeReply(tc, 405, "text/plain", "", "Requested keytype is not supported");
                return;
            }
            /*Create the json object with the URI pair to return*/
            JSONObject response = new JSONObject();
            response.put("insertURI", insertUri);
            response.put("requestURI", requestUri);
            /*Send the reply*/
            writeReply(tc, 200, "application/json", "", response.toJSONString());
        } catch (Exception e){
            throw e;
        }        
    }

    /**
     * isEnabled returns true
     * 
     * @param tc ToadletContext : The Context object
     * @return boolean
     */
    @Override
    public boolean isEnabled(ToadletContext tc) {
        return true;
    }
    
    /**
     * We allow POST without password
     * 
     * @return true
     */
    @Override
    public boolean allowPOSTWithoutPassword(){
       return true; 
    }
    
    /**
     * We disable extended method handling
     * 
     * @return false 
     */
    public boolean enableExtendedMethodHandling(){
        return false;
    }
    
    /**
     * Gets the action from the request URI.
     * The action is the third part of the path as it follows host and /rest/
     * 
     * @param uri URI : The URI of the request 
     * @return String
     * @throws Exception 
     */
    private String getActionFromUri(URI uri) throws Exception{
        try {
            return uri.toString().split("/")[2];
        }
        catch (Exception e){
            throw e;
        }
    }
    
    /**
     * Gets the params from a request to insert data
     * 
     * @param uri URI : The URI of the request 
     * @return Map<String,String> : Parameter name => Parameter value
     * @throws Exception 
     */
    private Map<String,String> getInsertParamsFromUri(URI uri) throws Exception{
        try {
            Map<String,String> params = new HashMap();
            String uriString = uri.getPath();
            String[] uriParts = uriString.split("/");
            params.put("action", uriParts[2]);
            params.put("key", uriParts[3]+"/");
            String filename = "";
            if (uriParts.length > 4){
                for (int i=4; i<uriParts.length; i++){
                    filename += uriParts[i];
                    if (i<uriParts.length-1){
                        filename += "/";
                    }
                }
            }
            params.put("filename", filename);
            return params;
        }
        catch (Exception e){
            throw e;
        }
    }
    
    /**
     * Gets the params from a request to generate a key
     * 
     * @param uri URI : The URI of the request 
     * @return Map<String,String> : Parameter name => Parameter value
     * @throws Exception 
     */
    private Map<String,String> getKeygenParamsFromUri(URI uri) throws Exception{
        try {
            Map<String,String> params = new HashMap();
            String uriString = uri.getPath();
            String[] uriParts = uriString.split("/");
            params.put("action", uriParts[2]);
            params.put("keytype", uriParts[3].toUpperCase());
            return params;
        }
        catch (Exception e){
            throw e;
        }
    }
    
    /**
     * Utility function that reads an input stream to a Byte Array
     * 
     * @param input InputStream : The InputStream
     * @return byte[] 
     * @throws IOException 
     */
    private byte[] InputStreamToByteArray(InputStream input) throws IOException{
        byte[] buffer = new byte[8192];
        int bytesRead;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while ((bytesRead = input.read(buffer)) != -1)
        {
            output.write(buffer, 0, bytesRead);
        }
        return output.toByteArray();
    }
    
    /**
     * Inserts data
     * 
     * @param key String : The InsertURI for inserting the data
     * @param filename String : The file name is appended to the key
     * @param contenttype String : The contet-type of the inserted data
     * @param data Bucket : The actual data to be inserted 
     * @param priority short : The priority of the insert action
     * @param realtime boolean : If the insert action is realtime or not
     * @param tc ToadletContext : The Context object
     * @return InsertStatusCallback : Used for getting the outcome of the insert action 
     * @throws MalformedURLException
     * @throws IOException
     * @throws InsertException 
     */
    private InsertStatusCallback insert(String key, String filename, String contenttype, Bucket data, short priority, boolean realtime, ToadletContext tc) throws MalformedURLException, IOException, InsertException{
        FreenetURI targetUri = new FreenetURI(key+filename);
        targetUri.setDocName(filename);
        InsertContext insertContext = client.getInsertContext(true);
        byte[] databytes = InputStreamToByteArray(data.getInputStream());
        RandomAccessBucket bucket = new ArrayBucket(databytes);
        bucket.setReadOnly();
        ClientMetadata metadata = new ClientMetadata(contenttype);
        InsertBlock insertBlock = new InsertBlock(bucket, metadata, targetUri);
        InsertStatusCallback callback = new InsertStatusCallback(tc, bucket, realtime);
        ClientPutter clientPutter = client.insert(insertBlock, null, false, insertContext, callback, priority);
        callback.setClientPutter(clientPutter);
        return callback;
    }
    
    /**
     * Generate an SSK pair for a given filename
     * 
     * @param filename String : The filename
     * @return InsertableClientSSK : The generated key
     */
    private InsertableClientSSK keygenSSK(String filename){
        return keygenSSK(filename, -1);
    }
    
    /**
     * Generate an SSK pair for a given filename and a given version
     * 
     * @param filename String : The filename
     * @param version int : The version
     * @return InsertableClientSSK : The generated key 
     */
    private InsertableClientSSK keygenSSK(String filename, int version){
        final RandomSource r = node.random;
        if (version > -1){
            filename+="-"+version;
        }
        InsertableClientSSK key = InsertableClientSSK.createRandom(r, filename);
        return key;
    }
    
    private void handleRegName(URI uri, HTTPRequest httpr, ToadletContext tc) throws PluginNotFoundException, IOException, ToadletContextClosedException, InterruptedException {
        JSONParser parser = new JSONParser();
        try {
            JSONObject content = (JSONObject)parser.parse(new InputStreamReader(httpr.getRawData().getInputStream(), "UTF-8"));
            FCPPluginMessage message = regName((String)content.get("name"), (String)content.get("requestKey"));
            if (message.success){
                JSONObject response = new JSONObject();
                response.put("resolveURI", message.params.get("resolveURI"));
                writeReply(tc, 200, "application/json", "OK", response.toJSONString());
            }
            else {
                writeReply(tc, 500, "text/plain", "Error", "Error: "+message.errorCode+" "+message.errorMessage);
            }
            
        } catch (ParseException ex) {
            writeReply(tc, 400, "text/plain", "Bad Request", "JSON content decoding error "+ex.toString());
        }
    }
    
    private FCPPluginMessage regName(String name, String requestKey) throws PluginNotFoundException, IOException, InterruptedException{
        IndynetClientCallback callback = new IndynetClientCallback();
        FCPPluginConnection connection = pr.connectToOtherPlugin(indynetPluginName, callback);
        SimpleFieldSet params = new SimpleFieldSet(false);
        params.putSingle("action", "resolver.register");
        params.putSingle("name", name);
        params.putSingle("requestKey", requestKey);
        FCPPluginMessage message = FCPPluginMessage.construct(params, null);
        connection.send(message);
        return callback.getReturnedMessage();
    }
    
    public void handleResname(URI uri, HTTPRequest httpr, ToadletContext tc) throws Exception{
        try {
            String requestKey;
            
            Map<String,String> params = getResnameParamsFromUri(uri);
            String name = params.get("name");
            try {
                FCPPluginMessage message = resName(name);            
                /*Create the json object with the URI pair to return*/
                if (message.success){
                    /*Send the reply*/
                    writeReply(tc, 200, "application/json", "", message.params.get("json"));
                }
                else {
                    writeReply(tc, 500, "text/plain", "Error", "Error: "+message.errorCode+" "+message.errorMessage);
                }
            }
            catch (Exception e){
                writeReply(tc, 500, "text/plain", "Server Error", e.toString());
            }
        }
        catch (Exception ex){
            writeReply(tc, 400, "text/plain", "Bad Request", ex.toString());
        }        
    }
    
    private FCPPluginMessage resName(String name) throws PluginNotFoundException, IOException, InterruptedException{
        IndynetClientCallback callback = new IndynetClientCallback();
        FCPPluginConnection connection = pr.connectToOtherPlugin(indynetPluginName, callback);
        SimpleFieldSet params = new SimpleFieldSet(false);
        params.putSingle("action", "resolver.resolve");
        params.putSingle("name", name);
        FCPPluginMessage message = FCPPluginMessage.construct(params, null);
        connection.send(message);
        return callback.getReturnedMessage();
    }
    
    private Map<String,String> getResnameParamsFromUri(URI uri) throws Exception, NullPointerException{
        Map<String,String> params = new HashMap();
        String uriString = uri.getPath();
        String[] uriParts = uriString.split("/");
        params.put("action", uriParts[2]);
        params.put("name", uriParts[3].toLowerCase());
        return params;
    }
    
    /**
     * InsertStatusCallback is an implementation of ClientPutCallback. 
     * It gets notified when the insert proccess is finished either with success or with failure
     */
    private class InsertStatusCallback implements ClientPutCallback, RequestClient {
        public static final int STATUS_SUCCESS = 0;
        public static final int STATUS_FAILURE = 1;
        public static final int STATUS_CANCELLED = 2;
        final Lock lock = new ReentrantLock();
        final Condition finished = lock.newCondition();
        private final ToadletContext tc;
        private final RandomAccessBucket bucket;
        private ClientPutter clientPutter;
        private int status;
        private InsertException ie;
        private final boolean realtime;
        private FreenetURI insertedURI;
        
        /**
         * Constructor
         * 
         * @param tc ToadletContext : The Context object
         * @param bucket RandomAccessBucket : The Bucket to be inserted 
         * @param realtime boolean : Realtime insert or not
         */
        public InsertStatusCallback(ToadletContext tc, RandomAccessBucket bucket, boolean realtime){
            this.tc = tc;
            this.bucket = bucket;
            this.realtime = realtime;
        }
        
        /**
         * Setter for clientPutter
         * 
         * @param clientPutter ClientPutter : The ClientPutter object
         */
        public void setClientPutter(ClientPutter clientPutter) {
                this.clientPutter = clientPutter;
        }
        
        /**
         * Method to cancel the insert
         * 
         * When called the onging insert is cancelled and rhe bucket is destroyed 
         */
        public void cancel() {
            lock.lock();
            try {
                clientPutter.cancel(node.clientCore.clientContext);
                bucket.free();
                status = STATUS_CANCELLED;
                finished.signalAll();
            }
            finally{
                lock.unlock();
            }
        }
             
        /**
         * Returns the Exception thrown from a failed insert process
         * 
         * @return InsertException
         */
        public InsertException getInsertException(){
            return ie;
        }

        /**
         * Called when the URI of the inserted data is generated 
         * Currently it is a dummy function doing nothing 
         * 
         * @param furi FreenetURI : The inserted URI
         * @param bcp BaseClientPutter : The ClientPutter object
         */
        @Override
        public void onGeneratedURI(FreenetURI furi, BaseClientPutter bcp) {
            //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        /**
         * Called when the Metadata of the inserted data is generated 
         * Currently it is a dummy function doing nothing 
         * 
         * @param bucket Bucket : The Bucket to be inserted
         * @param bcp BaseClientPutter : The ClientPutter object
         */
        @Override
        public void onGeneratedMetadata(Bucket bucket, BaseClientPutter bcp) {
            //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        /**
         * Called when the Metadata of the inserted data is fetchable (?? Not sure)
         * Currently it is a dummy function doing nothing 
         * 
         * @param bcp BaseClientPutter : The ClientPutter object
         */
        @Override
        public void onFetchable(BaseClientPutter bcp) {
            //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        /**
         * Called when the insert process has finished with success 
         * 
         * @param bcp BaseClientPutter : The ClientPutter object
         */
        @Override
        public void onSuccess(BaseClientPutter bcp) {
            lock.lock();
            try{
                status = STATUS_SUCCESS;
                insertedURI = bcp.getURI();
                bucket.free();
                finished.signalAll();
            }
            finally{
                lock.unlock();
            }
        }

        /**
         * Called when the insert process has failed 
         * 
         * @param ie InsertException : The generated Exception
         * @param bcp BaseClientPutter : The ClientPutter object
         */
        @Override
        public void onFailure(InsertException ie, BaseClientPutter bcp) {
            lock.lock();
            try{
                status = STATUS_FAILURE;
                bucket.free();
                this.ie = ie;
                finished.signalAll();
            }
            finally{
                lock.unlock();
            }
        }

        /**
         * Called when the insert process is resumed
         * Currently it is a dummy function doing nothing 
         * 
         * @param cc The Context object
         * @throws ResumeFailedException 
         */
        @Override
        public void onResume(ClientContext cc) throws ResumeFailedException {
            //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        /**
         * Returns the self object
         * 
         * @return RequestClient
         */
        @Override
        public RequestClient getRequestClient() {
            return this;
        }

        /**
         * This insert process is not persistent
         * 
         * @return boolean
         */
        @Override
        public boolean persistent() {
            return false;
        }

        /**
         * Returns the realtime flag
         * 
         * @return boolean 
         */
        @Override
        public boolean realTimeFlag() {
            return realtime;
        }
        
        /**
         * Returns the status of the insertion
         * 
         * @return int
         */
        public int getStatus() throws InterruptedException{
            lock.lock();
            try{
                finished.await();
                return status;
            }
            finally{
                lock.unlock();
            }
        }
        
        /**
         * Returns the URI of the inserted data after insert success
         * 
         * @return FreenetURI
         */
        public FreenetURI getInsertedURI() throws InterruptedException{
            
            return insertedURI;
           
        }
        
    }
    
    private class IndynetClientCallback implements ClientSideFCPMessageHandler {

        final Lock lock = new ReentrantLock();
        final Condition finished = lock.newCondition();
        FCPPluginMessage message = null;
        
        @Override
        public FCPPluginMessage handlePluginFCPMessage(FCPPluginConnection fcppc, FCPPluginMessage fcppm) {
            lock.lock();
            try {
                message = fcppm;
                finished.signalAll();
                return FCPPluginMessage.construct();
            }
            finally {
                lock.unlock();
            }
        }
        
        public FCPPluginMessage getReturnedMessage() throws InterruptedException{
            lock.lock();
            try {
                finished.await();
                return message;
            }
            finally {
                lock.unlock();
            }
        }
                
    }
}
