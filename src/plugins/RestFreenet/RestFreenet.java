/**
 * This plugin exposes freenet functions via a REST API.
 * 
 * The API is accessible under http://<freenet host>:<freenet port>/rest/
 * Currently the following functions are supported:
 * - Generation of SSK or USK key pair: 
 *      GET http://<freenet host>:<freenet port>/rest/keygen/<keytype>[?filename=<filename>&version=<version>]
 *      Returns 200 response with a JSON object containing an InsertURI, requestURI pair of type <keytype> as data,
 *      or an error code with text error description on failure.
 *      If <filename> is provided a URI pair for inserting and requesting the specific filename is returned.
 *      If <filename> and <version> are provided a URI pair for inserting and requesting 
 *      the specific filename and version is returned.
 *      e.g. GET http://localhost:8888/rest/keygen/usk?filename=hello.txt&version=3
 * - Insert data:
 *      POST http://<freenet host>:<freenet port>/rest/insert/<insertURI>[?priority=<priority num>&realtime=<0|1>
 *      Inserts the data sent as request content to provided insertURI.
 *      If priority number is provided the priority of the insert is set to that value. Defualt is 1 (Interactive).
 *      If realtime boolean value is provided realtime for insert is set respectively. Default is false. 
 *      Returns 200 response with no data on success, or an error code with text error description on failure.
 *      e.g. POST http://localhost:8888/rest/insert/USK@GHe[...]PO/HelloWorld.txt/0?priority=1&realtime=0
 * 
 * @version 0.1
 * @author Konstantinos Togias <info@ktogias.gr>
 */

package plugins.RestFreenet;
import freenet.clients.http.ToadletContainer;
import freenet.pluginmanager.*;


/**
 * The Plugin class
 * 
 * @author Konstantinos Togias <info@ktogias.gr>
 */
public class RestFreenet implements FredPlugin {
        private volatile boolean goon = true; //A boolean for main loop. When false plugin main loop is stopped.
	PluginRespirator pr; //The PluginRespirator object provided when runPlugin method is called.
        String basePath = "/rest/"; //The base path under which the pugin is accessed. 

        /**
         * Implementation of terminate method. 
         * We just do goon false so the main loop exists.
         */
	public void terminate() {
		goon = false;
	}

        /**
         * Implementation of runPlugin method.
         * This method runs when the plugin is enabled.
         * @param pr PluginRespirator : The PluginRespirator object
         */
	public void runPlugin(PluginRespirator pr) {
		this.pr = pr;
                ToadletContainer tc = pr.getToadletContainer(); //Get the container
                RestToadlet rt = new RestToadlet(basePath, pr.getHLSimpleClient(), pr.getNode()); //Create the Toadlet that handles the HTTP requests
                tc.register(rt, null, rt.path(), true, false); //Resgister the Toadlet to the container
                /* This loop needs to constantly 
                 * run for the plugin to be listed in loaded plugins list 
                 * in the plugins page 
                 */
                while(goon) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// Who cares ?
			}
		}
	}
	
}
