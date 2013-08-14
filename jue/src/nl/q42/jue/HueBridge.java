package nl.q42.jue;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import nl.q42.jue.HttpClient.Result;
import nl.q42.jue.exceptions.ApiException;
import nl.q42.jue.exceptions.DeviceOffException;
import nl.q42.jue.exceptions.EntityNotAvailableException;
import nl.q42.jue.exceptions.GroupTableFullException;
import nl.q42.jue.exceptions.LinkButtonException;
import nl.q42.jue.exceptions.UnauthorizedException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Representation of a connection with a Hue bridge.
 */
public class HueBridge {
	private final static String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";
	
	private String ip;
	private String username;
	
	private Gson gson = new GsonBuilder().setDateFormat(DATE_FORMAT).create();
	private HttpClient http = new HttpClient();
	
	/**
	 * Connect with a bridge as a new user.
	 * @param ip ip address of bridge
	 */
	public HueBridge(String ip) {
		this.ip = ip;
	}
	
	/**
	 * Connect with a bridge as an existing user.
	 * @param ip ip address of bridge
	 * @param username username to authenticate with
	 */
	public HueBridge(String ip, String username) {
		this.ip = ip;
		this.username = username;
	}
	
	/**
	 * Set the connect and read timeout for HTTP requests.
	 * @param timeout timeout in milliseconds or 0 for indefinitely
	 */
	public void setTimeout(int timeout) {
		http.setTimeout(timeout);
	}
	
	/**
	 * Returns the username currently authenticated with or null if there isn't one.
	 * @return username or null
	 */
	public String getUsername() {
		return username;
	}
	
	/**
	 * Returns a list of lights known to the bridge.
	 * @return list of known lights 
	 * @throws UnauthorizedException thrown if the user no longer exists
	 */
	public List<Light> getLights() throws IOException, ApiException {
		requireAuthentication();
		
		Result result = http.get(getRelativeURL("lights"));
		
		handleErrors(result);
			
		Map<String, Light> lightMap = safeFromJson(result.getBody(), Light.gsonType);
		
		ArrayList<Light> lightList = new ArrayList<Light>();
		
		for (String id : lightMap.keySet()) {
			Light light = lightMap.get(id);
			light.setId(id);
			lightList.add(light);
		}
		
		return lightList;
	}
	
	/**
	 * Returns the last time a search for new lights was started.
	 * If a search is currently running, the current time will be
	 * returned or null if a search has never been started.
	 * @return last search time
	 * @throws UnauthorizedException thrown if the user no longer exists
	 */
	public Date getLastSearch() throws IOException, ApiException {
		requireAuthentication();
		
		Result result = http.get(getRelativeURL("lights/new"));
		
		handleErrors(result);
		
		String lastScan = safeFromJson(result.getBody(), NewLightsResponse.class).lastscan;
		
		if (lastScan.equals("none")) {
			return null;
		} else if (lastScan.equals("active")) {
			return new Date();
		} else {
			try {
				return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(lastScan);
			} catch (ParseException e) {
				return null;
			}
		}
	}
	
	/**
	 * Start searching for new lights for 1 minute.
	 * A maximum amount of 15 new lights will be added.
	 * @throws UnauthorizedException thrown if the user no longer exists
	 */
	public void startSearch() throws IOException, ApiException {
		requireAuthentication();
		
		Result result = http.post(getRelativeURL("lights"), "");
		
		handleErrors(result);
	}
	
	/**
	 * Returns detailed information for the given light.
	 * @param light light
	 * @return detailed light information
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if the specified light no longer exists
	 */
	public FullLight getLight(Light light) throws IOException, ApiException {
		return getLight(light.getId());
	}
	
	/**
	 * Returns detailed information for the given light.
	 * @param id light id
	 * @return detailed light information
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if a light with the given id doesn't exist 
	 */
	public FullLight getLight(String id) throws IOException, ApiException {
		requireAuthentication();
		
		Result result = http.get(getRelativeURL("lights/" + enc(id)));
		
		handleErrors(result);
		
		FullLight fullLight = safeFromJson(result.getBody(), FullLight.class);
		fullLight.setId(id);
		return fullLight;
	}
	
	/**
	 * Changes the name of the light and returns the new name.
	 * A number will be appended to duplicate names, which may result in a new name exceeding 32 characters.
	 * @param light light
	 * @param name new name [0..32]
	 * @return new name
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if the specified light no longer exists
	 */
	public String setLightName(Light light, String name) throws IOException, ApiException {
		requireAuthentication();
		
		String body = gson.toJson(new SetAttributesRequest(name));
		Result result = http.put(getRelativeURL("lights/" + enc(light.getId())), body);
		
		handleErrors(result);
		
		List<SuccessResponse> entries = safeFromJson(result.getBody(), SuccessResponse.gsonType);
		SuccessResponse response = entries.get(0);
		
		return (String) response.success.get("/lights/" + enc(light.getId()) + "/name");
	}
	
	/**
	 * Changes the state of a light.
	 * @param light light
	 * @param update changes to the state
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if the specified light no longer exists
	 * @throws DeviceOffException thrown if the specified light is turned off
	 */
	public void setLightState(Light light, StateUpdate update) throws IOException, ApiException {
		requireAuthentication();
		
		String body = update.toJson();
		Result result = http.put(getRelativeURL("lights/" + enc(light.getId()) + "/state"), body);
		
		handleErrors(result);
	}
	
	/**
	 * Returns the list of groups.
	 * @return list of groups
	 * @throws UnauthorizedException thrown if the user no longer exists
	 */
	public List<Group> getGroups() throws IOException, ApiException {
		requireAuthentication();
		
		Result result = http.get(getRelativeURL("groups"));
		
		handleErrors(result);
		
		Map<String, Group> groupMap = safeFromJson(result.getBody(), Group.gsonType);
		ArrayList<Group> groupList = new ArrayList<Group>();
		
		groupList.add(new Group());
		
		for (String id : groupMap.keySet()) {
			Group group = groupMap.get(id);
			group.setId(id);
			groupList.add(group);
		}
		
		return groupList;
	}
	
	/**
	 * Creates a new group and returns it.
	 * Due to API limitations, the name of the returned object
	 * will simply be "Group". The bridge will append a number to this
	 * name if it's a duplicate. To get the final name, call getGroup
	 * with the returned object.
	 * @param lights lights in group
	 * @return object representing new group
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws GroupTableFullException thrown if the group limit has been reached
	 */
	public Group createGroup(List<Light> lights) throws IOException, ApiException {
		requireAuthentication();
		
		String body = gson.toJson(new SetAttributesRequest(lights));
		Result result = http.post(getRelativeURL("groups"), body);
		
		handleErrors(result);
		
		List<SuccessResponse> entries = safeFromJson(result.getBody(), SuccessResponse.gsonType);
		SuccessResponse response = entries.get(0);
		
		Group group = new Group();
		group.setName("Group");
		group.setId(Util.quickMatch("^/groups/([0-9]+)$", (String) response.success.values().toArray()[0]));
		return group;
	}
	
	/**
	 * Creates a new group and returns it.
	 * Due to API limitations, the name of the returned object
	 * will simply be the same as the name parameter. The bridge will
	 * append a number to the name if it's a duplicate. To get the final
	 * name, call getGroup with the returned object.
	 * @param name new group name
	 * @param lights lights in group
	 * @return object representing new group
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws GroupTableFullException thrown if the group limit has been reached
	 */
	public Group createGroup(String name, List<Light> lights) throws IOException, ApiException {
		requireAuthentication();
		
		String body = gson.toJson(new SetAttributesRequest(name, lights));
		Result result = http.post(getRelativeURL("groups"), body);
		
		handleErrors(result);
		
		List<SuccessResponse> entries = safeFromJson(result.getBody(), SuccessResponse.gsonType);
		SuccessResponse response = entries.get(0);
		
		Group group = new Group();
		group.setName(name);
		group.setId(Util.quickMatch("^/groups/([0-9]+)$", (String) response.success.values().toArray()[0]));
		return group;
	}
	
	/**
	 * Returns detailed information for the given group.
	 * @param group group
	 * @return detailed group information
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if the specified group no longer exists
	 */
	public FullGroup getGroup(Group group) throws IOException, ApiException {
		return getGroup(group.getId());
	}
	
	/**
	 * Returns detailed information for the given group.
	 * @param id group id
	 * @return detailed group information
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if a group with the given id doesn't exist
	 */
	public FullGroup getGroup(String id) throws IOException, ApiException {
		requireAuthentication();
		
		Result result = http.get(getRelativeURL("groups/" + enc(id)));
		
		handleErrors(result);
		
		FullGroup fullGroup = safeFromJson(result.getBody(), FullGroup.class);
		fullGroup.setId(id);
		return fullGroup;
	}
	
	/**
	 * Changes the name of the group and returns the new name.
	 * A number will be appended to duplicate names, which may result in a new name exceeding 32 characters.
	 * @param group group
	 * @param name new name [0..32]
	 * @return new name
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if the specified group no longer exists
	 */
	public String setGroupName(Group group, String name) throws IOException, ApiException {
		requireAuthentication();
		
		if (!group.isModifiable()) {
			throw new IllegalArgumentException("Group cannot be modified");
		}
		
		String body = gson.toJson(new SetAttributesRequest(name));
		Result result = http.put(getRelativeURL("groups/" + enc(group.getId())), body);
		
		handleErrors(result);
		
		List<SuccessResponse> entries = safeFromJson(result.getBody(), SuccessResponse.gsonType);
		SuccessResponse response = entries.get(0);
		
		return (String) response.success.get("/groups/" + enc(group.getId()) + "/name");
	}
	
	/**
	 * Changes the lights in the group.
	 * @param group group
	 * @param lights new lights [1..16]
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if the specified group no longer exists
	 */
	public void setGroupLights(Group group, List<Light> lights) throws IOException, ApiException {
		requireAuthentication();
		
		if (!group.isModifiable()) {
			throw new IllegalArgumentException("Group cannot be modified");
		}
		
		String body = gson.toJson(new SetAttributesRequest(lights));
		Result result = http.put(getRelativeURL("groups/" + enc(group.getId())), body);
		
		handleErrors(result);
	}
	
	/**
	 * Changes the name and the lights of a group and returns the new name.
	 * @param group group
	 * @param name new name [0..32]
	 * @param lights [1..16]
	 * @return new name
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if the specified group no longer exists
	 */
	public String setGroupAttributes(Group group, String name, List<Light> lights) throws IOException, ApiException {
		requireAuthentication();
		
		if (!group.isModifiable()) {
			throw new IllegalArgumentException("Group cannot be modified");
		}
		
		String body = gson.toJson(new SetAttributesRequest(name, lights));
		Result result = http.put(getRelativeURL("groups/" + enc(group.getId())), body);
		
		handleErrors(result);
		
		List<SuccessResponse> entries = safeFromJson(result.getBody(), SuccessResponse.gsonType);
		SuccessResponse response = entries.get(0);
		
		return (String) response.success.get("/groups/" + enc(group.getId()) + "/name");
	}
	
	/**
	 * Changes the state of a group.
	 * @param group group
	 * @param update changes to the state
	 * @throws UnauthorizedException thrown if the user no longer exists
	 * @throws EntityNotAvailableException thrown if the specified group no longer exists
	 */
	public void setGroupState(Group group, StateUpdate update) throws IOException, ApiException {
		requireAuthentication();
		
		String body = update.toJson();
		Result result = http.put(getRelativeURL("groups/" + enc(group.getId()) + "/action"), body);
		
		handleErrors(result);
	}
	
	/**
	 * Delete a group.
	 * @param group group
 	 * @throws UnauthorizedException thrown if the user no longer exists
 	 * @throws EntityNotAvailableException thrown if the specified group no longer exists
	 */
	public void deleteGroup(Group group) throws IOException, ApiException {
		requireAuthentication();
		
		if (!group.isModifiable()) {
			throw new IllegalArgumentException("Group cannot be modified");
		}
		
		Result result = http.delete(getRelativeURL("groups/" + enc(group.getId())));
		
		handleErrors(result);
	}
	
	/**
	 * Link with bridge using the specified username and device type.
	 * @param username username for new user [10..40]
	 * @param devicetype identifier of application [0..40]
	 * @throws LinkButtonException thrown if the bridge button has not been pressed
	 */
	public void link(String username, String devicetype) throws IOException, ApiException {
		this.username = link(new CreateUserRequest(username, devicetype));
	}
	
	/**
	 * Link with bridge using the specified device type. A random valid username will be generated by the bridge and returned.
	 * @return new random username generated by bridge
	 * @param devicetype identifier of application [0..40]
	 * @throws LinkButtonException thrown if the bridge button has not been pressed
	 */
	public String link(String devicetype) throws IOException, ApiException {
		return (this.username = link(new CreateUserRequest(devicetype)));
	}
	
	private String link(CreateUserRequest request) throws IOException, ApiException {
		if (this.username != null) {
			throw new IllegalStateException("already linked");
		}
		
		String body = gson.toJson(request);
		Result result = http.post(getRelativeURL(""), body);
		
		handleErrors(result);
		
		List<SuccessResponse> entries = safeFromJson(result.getBody(), SuccessResponse.gsonType);
		SuccessResponse response = entries.get(0);
	
		return (String) response.success.get("username");
	}
	
	/**
	 * Returns basic configuration of the bridge (name and firmware version) and
	 * more detailed info if there is an authenticated user.
	 * @see Config
	 * @return Config or AuthenticatedConfig if authenticated
	 */
	public Config getConfig() throws IOException, ApiException {
		Result result = http.get(getRelativeURL("config"));
		handleErrors(result);
		
		if (username == null) {
			return safeFromJson(result.getBody(), Config.class);
		} else {
			return safeFromJson(result.getBody(), AuthenticatedConfig.class);
		}
	}
	
	public void setConfig(ConfigUpdate update) throws IOException, ApiException {
		requireAuthentication();
		
		String body = update.toJson();
		Result result = http.put(getRelativeURL("config"), body);
		
		handleErrors(result);
	}
	
	/**
	 * Unlink the current user from the bridge.
	 * @throws UnauthorizedException thrown if the user no longer exists
	 */
	public void unlink() throws IOException, ApiException {
		requireAuthentication();
		
		Result result = http.delete(getRelativeURL("config/whitelist/" + enc(username)));

		handleErrors(result);
	}
	
	/**
	 * Returns the entire bridge configuration.
	 * This request is rather resource intensive for the bridge,
	 * don't use it more often than necessary. Prefer using requests for
	 * specific information your app needs.
	 * @return full bridge configuration
	 * @throws UnauthorizedException thrown if the user no longer exists
	 */
	public FullConfig getFullConfig() throws IOException, ApiException {
		requireAuthentication();
		
		Result result = http.get(getRelativeURL(""));
		
		handleErrors(result);
		
		return gson.fromJson(result.getBody(), FullConfig.class);
	}
	
	// Used as assert in requests that require authentication
	private void requireAuthentication() {
		if (this.username == null) {
			throw new IllegalStateException("linking is required before interacting with the bridge");
		}
	}
	
	// Methods that convert gson exceptions into ApiExceptions
	private <T> T safeFromJson(String json, Type typeOfT) throws ApiException {
		try {
			return gson.fromJson(json, typeOfT);
		} catch (JsonParseException e) {
			throw new ApiException("API returned unexpected result: " + e.getMessage());
		}
	}
	
	private <T> T safeFromJson(String json, Class<T> classOfT) throws ApiException {
		try {
			return gson.fromJson(json, classOfT);
		} catch (JsonParseException e) {
			throw new ApiException("API returned unexpected result: " + e.getMessage());
		}
	}
	
	// Used as assert in all requests to elegantly catch common errors
	private void handleErrors(Result result) throws IOException, ApiException {
		if (result.getResponseCode() != 200) {
			throw new IOException();
		} else {
			try {
				List<ErrorResponse> errors = gson.fromJson(result.getBody(), ErrorResponse.gsonType);
				if (errors == null) return;
				
				for (ErrorResponse error : errors) {					
					switch (error.getType()) {
					case 1:
						username = null;
						throw new UnauthorizedException(error.getDescription());
					case 3:
						throw new EntityNotAvailableException(error.getDescription());
					case 101:
						throw new LinkButtonException(error.getDescription());
					case 201:
						throw new DeviceOffException(error.getDescription());
					case 301:
						throw new GroupTableFullException(error.getDescription());
					default:
						throw new ApiException(error.getDescription());
					}
				}
			} catch (JsonParseException e) {
				// Not an error
			} catch (NullPointerException e) {
				// Object that looks like error
			}
		}
	}
	
	// UTF-8 URL encode
	private String enc(String str) {
		try {
			return URLEncoder.encode(str, "utf-8");
		} catch (UnsupportedEncodingException e) {
			// throw new EndOfTheWorldException()
			throw new UnsupportedOperationException("UTF-8 not supported");
		}
	}
	
	private String getRelativeURL(String path) {
		if (username == null) {
			return "http://" + ip + "/api/" + path;
		} else {
			return "http://" + ip + "/api/" + enc(username) + "/" + path;
		}
	}
}
