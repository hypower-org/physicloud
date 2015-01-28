package physicloud;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class PhysiCloudClient {
	
	//fields -
	private Thread worker;	//thread for doing the work
	private volatile boolean stopRequested; //boolean for stopping thread gracefully
	private Socket pcClient; //client socket for communication with Physicloud network over TCP
	private ObjectOutputStream out; //object out stream for reading objects from the Physicloud network
	private ObjectInputStream in;  //object in stream for writing objects to the Physicloud network
	private ConcurrentHashMap<String, Double> currentData;  //concurrent map for incoming data
	
	//constructor - start worker thread in boolean-dependent loop
	public PhysiCloudClient(){
		currentData = new ConcurrentHashMap<String, Double>();
		stopRequested = false;
		worker = new Thread(){
			public void run(){
				while(!stopRequested){
					getPCData();
					try{
						Thread.sleep(50);
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
	public void requestStop(){
		stopRequested = true;
		if(worker != null){
			worker.interrupt();
		}
	}
	
	//method to receive data from physicloud network and update
	//concurrent data structure (synchronized for atomicity)
	@SuppressWarnings("unchecked")
	private void getPCData() {
		try {
			HashMap<String, Double> dataIn = (HashMap<String, Double>) in.readObject();
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
	
	//method for MATLAB users to send commands to physicloud
	public void cmd(String cmd){
		try {out.writeObject(cmd);} 
		catch (IOException e) {e.printStackTrace();}
	}
}
