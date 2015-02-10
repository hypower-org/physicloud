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
		connectToPhysiCloud();
		worker.start();
	}
	
	//method for gracefully ending worker thread
	public void stopPC(){
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
	private void connectToPhysiCloud(){
		try {
			pcClient = new Socket("127.0.0.1", 8756);
			out = new ObjectOutputStream(pcClient.getOutputStream());
			in = new ObjectInputStream(pcClient.getInputStream());
		}
		catch (IOException e) {e.printStackTrace();}
	}
	
	@Deprecated
	//method for MATLAB users to retrieve data
	public Double[] pullData(){
		Double[] data = new Double[3];
		if(currentData!= null){
			data[0] = (Double) currentData.get("x");
			data[1] = (Double) currentData.get("y");
			data[2] = (Double) currentData.get("theta");
		}
		return data;
	}
	
	@Deprecated
	//method for MATLAB users to send commands to physicloud
	public void cmd(Object cmd){
		try {
			out.writeObject(cmd);
		} 
		catch (IOException e) {e.printStackTrace();}
	}
	//method for MATLAB users to get the state data of
	// a particular robot
	@SuppressWarnings("unchecked")
	public Double[] getData(String id){
		Double[] data = new Double[3];
		if(currentData != null){
			if(currentData.containsKey(id)){
				Vector<Object> robotState = (Vector<Object>) currentData.get(id);
				data[0] = (Double) robotState.get(0);
				data[1] = (Double) robotState.get(1);
				data[2] = (Double) robotState.get(2);
			}
			else{
				System.out.println("Error: Robot with that ID does not exist");
			}
		}
		return data;		
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
}