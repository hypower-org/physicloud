package physicloud;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

public class PhysiCloudClient {
	
	//fields -
	private Thread worker;	//thread for doing the work
	private volatile boolean stopRequested; //boolean for stopping thread gracefully
	private Socket pcClient; //client socket for communication with Physicloud network over TCP
	private ObjectOutputStream out; //object out stream for reading objects from the Physicloud network
	private ObjectInputStream in;  //object in stream for writing objects to the Physicloud network
	private ConcurrentHashMap<String, Object> currentData;  //concurrent map for incoming data
	
	//constructor - start worker thread in boolean-dependent loop
	public PhysiCloudClient(){
		currentData = new ConcurrentHashMap<String, Object>();
		stopRequested = false;
		worker = new Thread(){
			public void run(){
				while(!stopRequested){
					getPCData();
					try{
						Thread.sleep(100);
					}catch(InterruptedException e){
						System.out.println("Physicould client terminated");
						Thread.currentThread().interrupt();
					}
				}
			}
		};
		
		try {
			connectToPhysiCloud();
		}
		catch (InterruptedException e) {e.printStackTrace();}
		
		worker.start();
	}
	
	//method for gracefully ending worker thread
	public void kill(){
		stopRequested = true;
		if(worker != null){
			worker.interrupt();
		}
	}
	
	//method to receive data from physicloud network and update
	//concurrent data structure
	@SuppressWarnings("unchecked")
	private void getPCData() {
		try {
			HashMap<String, Object> dataIn = (HashMap<String, Object>) in.readObject();
			currentData.putAll(dataIn);
		}
		catch (ClassNotFoundException e) {e.printStackTrace();}
		catch (IOException e) {}	
	}

	//utility method for connecting TCP client
	private void connectToPhysiCloud() throws InterruptedException{
		Boolean connected = false;
		while(!connected){
			try {
				pcClient = new Socket("127.0.0.1", 8756);
				out = new ObjectOutputStream(pcClient.getOutputStream());
				in = new ObjectInputStream(pcClient.getInputStream());
				connected = true;
			}
			catch (IOException e) {
				System.out.println("Server not up...");
				Thread.sleep(1000);
			}
		}
	}
	
	//method for MATLAB users to get the state data of
	// a particular robot
	@SuppressWarnings("unchecked")
	public double[] getData(String id){
		double[] data = new double[3];
		if(currentData != null){
			if(currentData.containsKey(id)){
				Vector<Double> robotState = (Vector<Double>) currentData.get(id);
				data[0] =  robotState.get(0).doubleValue();
				data[1] =  robotState.get(1).doubleValue();
				data[2] =  robotState.get(2).doubleValue();
			}
			else{
				System.out.println("Error: Robot with that ID does not exist");
			}
		}
		return data;		
	}
	
	//method for MATLAB users to send a "go-to" command to a specific robot
	public void goTo(String robotId, Double xVal, Double yVal){
		HashMap<String, Object> locationMap = new HashMap<String, Object>();
		locationMap.put("command", "go-to");
		Vector<Double> locations =  new Vector<Double>(2);
		locations.add(0, xVal);
		locations.add(1, yVal);
		locationMap.put(robotId, locations);
		try {
			out.writeObject(locationMap);
		} 
		catch (IOException e) {e.printStackTrace();}
	}
	
	//method for MATLAB users to send a "go-to" command to a variable number of robots
	public void goTo(String[] robotIds, Double[] xVals, Double[] yVals){
		HashMap<String, Object> locationMap = new HashMap<String, Object>();
		for (int i = 0; i < robotIds.length; i++){
			Vector<Double> locations =  new Vector<Double>(2);
			locations.add(0, xVals[i]);
			locations.add(1, yVals[i]);
			locationMap.put(robotIds[i], locations);
		}
		locationMap.put("command", "go-to");
		try {
			out.writeObject(locationMap);
		} 
		catch (IOException e) {e.printStackTrace();}
	}
	
	//method for MATLAB users to stop all robots' movement
	public void stop(){
		HashMap<String, Object> stopMap = new HashMap<String, Object>();
		stopMap.put("command", "stop");
		try {
			out.writeObject(stopMap);
		} 
		catch (IOException e) {e.printStackTrace();}
	}
	
	//method for MATLAB users to stop a specific robot's movement
	public void stop(String robotId){
		HashMap<String, Object> stopMap = new HashMap<String, Object>();
		stopMap.put("command", "stop");
		Vector<String> ids =  new Vector<String>();
		ids.add(robotId);
		stopMap.put("ids", ids);
		try {
			out.writeObject(stopMap);
		} 
		catch (IOException e) {e.printStackTrace();}
	}
	
	//method for MATLAB users to stop a given set of robots' movement
	public void stop(String[] robotIds){
		HashMap<String, Object> stopMap = new HashMap<String, Object>();
		stopMap.put("command", "stop");
		Vector<String> ids =  new Vector<String>();
		for (int i = 0; i < robotIds.length; i++){
			ids.add(robotIds[i]);
		}
		stopMap.put("ids", ids);
		try {
			out.writeObject(stopMap);
		} 
		catch (IOException e) {e.printStackTrace();}
	}
	
	//method for MATLAB users to drive all robots at v, w
	public void drive(Double v, Double w){
		HashMap<String, Object> driveMap = new HashMap<String, Object>();
		driveMap.put("command", "drive");
		driveMap.put("v", v);
		driveMap.put("w", w);
		try {
			out.writeObject(driveMap);
		} 
		catch (IOException e) {e.printStackTrace();}
	}
	
	//method for MATLAB users to drive a specific robot at v, w
	public void drive(String robotId, Double v, Double w){
		HashMap<String, Object> driveMap = new HashMap<String, Object>();
		driveMap.put("command", "drive");
		Vector<String> ids =  new Vector<String>();
		ids.add(robotId);
		driveMap.put("ids", ids);
		driveMap.put("v", v);
		driveMap.put("w", w);
		try {
			out.writeObject(driveMap);
		}
		catch (IOException e) {e.printStackTrace();}
	}
	
	//method for MATLAB users to drive a given set of robots at v, w
	public void drive(String[] robotIds, Double v, Double w){
		HashMap<String, Object> driveMap = new HashMap<String, Object>();
		driveMap.put("command", "drive");
		Vector<String> ids =  new Vector<String>();
		for (int i = 0; i < robotIds.length; i++){
			ids.add(robotIds[i]);
		}
		driveMap.put("ids", ids);
		driveMap.put("v", v);
		driveMap.put("w", w);
		try {
			out.writeObject(driveMap);
		}
		catch (IOException e) {e.printStackTrace();}
	}

	
	//method for MATLAB users to zero a specified state variable (x, y, t) for a specific robot
	//pass "all" as var to zero all state variables (zero-ing theta puts it at pi/2)
	public void zero(String robotId, String var){
		HashMap<String, Object> zeroMap = new HashMap<String, Object>();
		zeroMap.put("command", "zero");
		Vector<String> ids =  new Vector<String>();
		ids.add(robotId);
		zeroMap.put("ids", ids);
		if(var.toLowerCase().equals("x")){
			zeroMap.put("x", "zero");
		}
		if(var.toLowerCase().equals("y")){
			zeroMap.put("y", "zero");
		}
		if(var.toLowerCase().equals("t")){
			zeroMap.put("t", "zero");
		}
		if(var.toLowerCase().equals("all")){
			zeroMap.put("x", "zero");
			zeroMap.put("y", "zero");
			zeroMap.put("t", "zero");
		}
		try {
			out.writeObject(zeroMap);
		} 
		catch (IOException e) {e.printStackTrace();}
	}
	//method for MATLAB users to zero a specified state variable (x, y, t) for a specific robot
	//pass "all" as var to zero all state variables (zero-ing theta puts it at pi/2)
	public void zero(String var){
		HashMap<String, Object> zeroMap = new HashMap<String, Object>();
		zeroMap.put("command", "zero");
		if(var.toLowerCase().equals("x")){
			zeroMap.put("x", "zero");
		}
		if(var.toLowerCase().equals("y")){
			zeroMap.put("y", "zero");
		}
		if(var.toLowerCase().equals("t")){
			zeroMap.put("t", "zero");
		}
		if(var.toLowerCase().equals("all")){
			zeroMap.put("x", "zero");
			zeroMap.put("y", "zero");
			zeroMap.put("t", "zero");
		}
		try {
			out.writeObject(zeroMap);
		} 
		catch (IOException e) {e.printStackTrace();}
	}
}