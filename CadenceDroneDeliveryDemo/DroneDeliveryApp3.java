package DroneMaths;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import org.apache.thrift.TException;

import com.uber.cadence.BadRequestError;
import com.uber.cadence.ClientVersionNotSupportedError;
import com.uber.cadence.DomainAlreadyExistsError;


import com.uber.cadence.RegisterDomainRequest;
import com.uber.cadence.ServiceBusyError;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowService.AsyncProcessor.DescribeWorkflowExecution;
import com.uber.cadence.activity.Activity;
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.client.ActivityCompletionClient;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.common.MethodRetry;
import com.uber.cadence.serviceclient.ClientOptions;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.worker.WorkerFactory;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;

import DroneMaths.DroneDeliveryApp2.DroneWorkflow;
import DroneMaths.DroneDeliveryApp2.OrderWorkflow;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

//import ExampleWorkflowApp3KafkaConsumer.ExampleWorkflow;

// Kafka producer
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.io.FileReader;
import java.io.IOException;
import java.time.Duration;
import java.util.Properties;


/*
 * 
 * Drone Delivery App - based on CourierApp
 * Assumptions: 1 drone base location at present, where charging occurs.
 * N drones
 * Orders come in, and once a drone is charged, it get an order, and then flies to order location, and then to delivery location.
 * Drones have 1 order at a time, and send location and state change updates via signals to the orders.
 * Once complete, drone flies back to base and starts charging.
 * Drone WFs last from start of charging to return to base, and have the same WF ID but different run IDs.
 * Just keeps going forever in current version.
 * 
 * Could be used to illusrate Kafka integration examples: (1) new order event in Kafka results in Order WF creation, (2) once drone is charged
 * it uses an activity and Kafka consumer (with 1 event read only) to wait for an order to come along, and then start the delivery process.
 * 
 * 
 * Extensions? Errors, and timeouts etc
 * For example, each step can have chance of failure with compensating actions, both Drone errors and delivery errors perhaps.
 * Can add more data to Drone - e.g. speed, direction, video images of trip etc.
 * 
 * Real locations!
 * 
 * Assume drones can fly 10km and 30m max, this is 20km/h max speed.
 * And they have to be able to return to base, with some charge left, say we can only use 0.8 of charge, so distance is reduced to 8k,.
 * and flying time 0.8 x 30m = 24m
 * Given that you have to fly to delivery point and back to base, max time is 12m and max distance is 4km
 * Assume pickup and delivery locations are on a line? Or compute total distance and FAIL if to great?
 * Could cheat and allocate 25% of distance for pickup, another 25% for delivery and 50% for return?
 * 
 * Assume GPS accuracy is 1m, and using decimal lat/lon 
 * 5 decimal places:	0.00001	0° 00′ 0.036″	individual trees, houses	1.11 m	1.02 m	0.787 m	0.435 m
 * Elevation as well?
 * how to compute location based on start and end location and speed?
 * distance = sqrt((lat1 - lat2)^2 + (lng1-lng2)^2)
 * What "units" are lat lon decimal in?
 * 
 * Version 2!
 * This one uses DroneMaths to update locations with "real" lat/lon coordinates.
 * base has lat/lon coordinations
 * It will need to construct a "flight plan" when it gets an Order with the location of the Order, and the location of the Delivery.
 * The location Loop will then need to compute next lat/lon coordinates based on where it is up to in the flight plan,
 * and then fly back to base at end of Delivery.
 * 
 * TODO Maybe make each delivery leg an activity which computes course and updates gps locations and then exits when it's reached the destination - easier than current approach!
 * can just reuse same activity with different start and end locations!
 * TODO Also add time scale - 1.0 = real-time, and then various speed ups allowed up to as fast as possible i.e. 0
 * This would update location instantly I guess.
 * Do we need to add timestamps somewhere I wonder?
 * 
 * Added exception handling for nextleg activity
 * now starts from current location.
 * Check timeouts as well
 * TODO Should we try some more complex error handling? I.e. if drone has "crashed" or been hit by a bird etc
 * or run out of power what should it do? Simplest example is if it's been blown off course and doesn't have sufficient power
 * to deliver then return to Order location or base?
 * 
 * Idea: Each step can fail with a specific probabilty and throw an exception - how does WF catch errors c.f. timeouts and restarts?
 * What compensation actions make sense
 * 
 * 31 Jan: Works ok with kafka topic supplying new orders ready for pickup, and Drone WF waiting to next job.
 * Need to increase partitions >= Drones, and add "real" data to the topic.
 * idea: probability of failure to pickup orders, and then the drone should give up and return to base, and
 * put the order onto a priority pickup topic. Also change so that Drones check that topic first before checking main job topic.
 * 
 * Order WF now generates it's own locations, and Drone WF queries the locations - in theory now the range may exceed drone so should check!
 * 
 * Also check how to fix random number in location generator - is there a way of annotating it with side effect?
 * Or just put location generator into order creator.
 * 
 * Idea! To have more Order WFs, make them delayed and/or repeating after some random interval.
 * But how to get them to replicate with correct locations but no other state?  Is there a clone with starting state or something?
 * Guess simplest solution is to pass locations to new WF instance and time interval.
 * 
 */

public class DroneDeliveryApp3 {
	
	// static String host  = "34.195.123.250";
	static String host = CommonProps.host;
	// static String domainName = "droneDemo";
	static final String orderjobsTopicName = CommonProps.orderjobsTopicName;
	
	// TODO Move this into top level class for sharing
	//static final String orderjobsTopicName = "orderjobs";  // Kafka topic to send orders waiting for drone allocation
	//static final String newordersTopicName = "neworders";  // Kafka topic to request new order WF creation
	
	static final String newordersTopicName = CommonProps.newordersTopicName;  // Kafka topic to request new order WF creation
	
	static final String domainName = CommonProps.domainName;
	
	static Properties kafkaProps;

	
	static final String orderActivityName = "OrderActivity";
	static final String droneActivityName = "DroneActivity";
	
	// where the drone departs and returns from
	static final double timeScale = 0; // 1.0 is real-time, 0 is as fast it can go
	static LatLon baseLocation = new LatLon(-35.20586, 149.09462);
	static double droneSpeed = 20.0; // max drone speed is 10km/h - assume it is either going or not going
	static double moveTime = 10.0; // time in s for each movement and location update
	static double pickupTime = 60.0; // seconds to collect order once arrived at location
	static double deliverTime = 60.0; // seconds to delivery the order once arrived at location
	static double initialCharge = 100.0; // assume Drones are fully charge when they leave base
	static final double maxFlightTime = 30 * 60; // s
	static double maxFlightDistance = droneSpeed * (maxFlightTime/(60*60)); // 10km for initial version
	// TODO change to more than 4.0 for safety margin?
	static double maxLegDistance = maxFlightDistance / 4.0; // assumes that getting to order location and delivering are 2 legs, leaving us 2 legs to get back to base
	static final double maxChargeTime = maxFlightTime/2.0; // assume recharging takes 1/2 of max flight time max
	

	// TODO Make this variable based on charge on return to base?
	// static int chargingTime = 1000;
	
	
	static WorkflowClient workflowClient = null;
	
	static int numOrders = 1;	// each drone only has 1 order at present - bigger drones may be possible in future
	static int numDroneDeliveries = 1; // number of times each drone will spawn a new WF instance before "retiring".
	

    // Workflow interface has to have at least one method annotated with @WorkflowMethod.
	// We want OrderWorkflow to have state changes, do we need 1 signal method or multiple? try 1 to start with.
    public interface OrderWorkflow {
        @WorkflowMethod(executionStartToCloseTimeoutSeconds = (int)maxFlightTime, taskList = orderActivityName)
        String startWorkflow(String name);
        @SignalMethod
        void updateGPSLocation(LatLon l);
		@SignalMethod
        void signalOrder(String msg);
        @SignalMethod
        void updateLocation(String loc);
        @QueryMethod
        String getState();
        @QueryMethod
        LatLon getOrderLocation();
        @QueryMethod
        LatLon getDeliveryLocation();
    }
    
    public interface DroneWorkflow {
        @WorkflowMethod(executionStartToCloseTimeoutSeconds = (int)maxFlightTime, taskList = droneActivityName)
        String startWorkflow(String name);
        @SignalMethod
        void updateGPSLocation(LatLon loc);
        @SignalMethod
        void updateCharge(double time);
		@SignalMethod
        void signalOrder(String msg);
        @SignalMethod
        void alive();
        @QueryMethod
        String getState();
        @QueryMethod
        LatLon getLatLon();
        @QueryMethod
        LatLon getNextLatLon();
    }
    
    // Order doesn't have many activities yet, but could send location updates to Kafka and check for transportation violations etc
    public interface OrderActivities
    {
    	@ActivityMethod(scheduleToCloseTimeoutSeconds = 60)
    	 String sendKafka(String name);	
    	@ActivityMethod(scheduleToCloseTimeoutSeconds = 60)
   	 	String readyForDelivery(String name);	
    }
    
    // What activities/tasks does Drone need?
    // should activities be related to state? E.g. "charging", get job, going to order location, going to delivery location, returning? etc
    public interface DroneActivities
    {
    	@ActivityMethod(scheduleToCloseTimeoutSeconds = 600)
    	 String createOrders(String name);	
    	// TODO How long to wait?! What if it times out waiting? 
    	@ActivityMethod(scheduleToCloseTimeoutSeconds = 600)
   	 	String waitForOrder(String name);	
    	// do we need a safety margin for recharge time?
    	
    	// @ActivityMethod(scheduleToCloseTimeoutSeconds = 100000, heartbeatTimeoutSeconds = 60)
    	@ActivityMethod(scheduleToCloseTimeoutSeconds = 100000)
   	 	// void locationLoop(String name, ArrayList<OrderWorkflow> orders);	
    	void locationLoop(String name, ArrayList<String> ids);
    	// New version which replaces locationLoop with general method for plotting course and moving from A to B and then returning when arrived.
    	// should we allow for failure of drone to arrive? Perhaps...
    	// timeout should be > time for flying each leg
    	// what if it fails and gets restarted?  Should pick up start location from current location each time?
    	// experiment with low timeout and see what happens...
    	@ActivityMethod(scheduleToCloseTimeoutSeconds = (int) maxFlightTime)
    	//@ActivityMethod(scheduleToCloseTimeoutSeconds = 1)
    	@MethodRetry(maximumAttempts = 2, initialIntervalSeconds = 1, expirationSeconds = 30, maximumIntervalSeconds = 30)
    	void nextLeg(LatLon start, LatLon end, boolean updateOrderLocation, String orderID);
    }
    
    public static class OrderWorkflowImpl implements OrderWorkflow {
    	
    	String orderName = "";
    	String newState = "";
    	String lastState = "";
    	
    	
    	ArrayList<String> updates = new ArrayList<String>();
    	ArrayList<String> locations = new ArrayList<String>();
    	ArrayList<LatLon> gpsLocations = new ArrayList<LatLon>();
    	
    	// current location
    	LatLon gpsLocation = null;
    	
    	LatLon startLocation = null;
    	LatLon deliveryLocation = null;

    	
    	private OrderActivities activities = null;
    	
    	public OrderWorkflowImpl() {
            this.activities = Workflow.newActivityStub(OrderActivities.class);
        }
    	
        @Override
        public String startWorkflow(String name) {
        	
        	
        	System.out.println("Started Order workflow " + name + ", ID=" + Workflow.getWorkflowInfo().getWorkflowId());
        	
        	// do we need activities? Not sure, try without to start with.
        	// E.g. an activity could be "get delivery address" etc
        	// Could also possibly send state update notifications rather than rely on signaller to do this?
        	// Note: We should also possibly enforce state transition rules here - i.e. can't go back to "ready" if delivery has started, and once delivered that's it!
        	
        	// Order is now responsible for it's own start and delivery location
        	// Note that in theory this could now result in orders being too far from drone base but currently force correct distance.
        	// TODO Need to wrap this as activity other random number generator in newDestination won't work.
        	// use side effect? https://cadenceworkflow.io/docs/java-client/side-effect/
        	
        	// int random = Workflow.sideEffect(Integer.class, () -> random.nextInt(100));
        	startLocation = Workflow.sideEffect(LatLon.class, () -> DroneMaths.newDestination(baseLocation, 0.1, maxLegDistance));
        	System.out.println("Order WF startLocation = " + startLocation.toString());
        	
        	// startLocation = DroneMaths.newDestination(baseLocation, 0.1, maxLegDistance);
            // deliveryLocation = DroneMaths.newDestination(startLocation, 0.1, maxLegDistance);
        	deliveryLocation = Workflow.sideEffect(LatLon.class, () -> DroneMaths.newDestination(startLocation, 0.1, maxLegDistance));
        	System.out.println("Order WF deliveryLocation = " + deliveryLocation.toString());

        	// A real activity - request a drone - could be triggered after some time period when order is actually ready in practice
        	activities.readyForDelivery(name);
        	
        	boolean delivered = false;
        	
        	// could put a "pull" getLocation(Drone Drone) call in here which is called every 60s?
        	// would this interfere with the signal from the Drone WF for state updates?
        	
        	// Note changed to have another orderComplete state to exit WF
        	String endState = "orderComplete";
        	
        	while (!delivered)
        	{
        		// will this work? Not sure...
        		Workflow.await(() -> newState != "");
        		System.out.println("order " + name + " got signal = " + newState);
        		updates.add(newState);
        		if (newState.equals(endState))
        		{
        			delivered = true;
        			System.out.println("Order WF exiting!");
        		}
        		// reset the state again so we don't get stuck in a loop
        		lastState = newState;
        		newState = "";
        		
        		
        		// for demo we could send event to Kafka for each update?
        		// activities.notifyUpdate(); 
        	}
            	
        	return "Order " + name + " " + endState;	
        }
        
        @Override
        public void signalOrder(String state) {
        	// System.out.println("Order " + orderName + " got a signal = " + state);
        	newState = state;
        }
        
        @Override
        public void updateLocation(String l) {
        	// System.out.println("Order " + orderName + " got a signal = " + state);
        	System.out.println("Order " + orderName + " new location = " + l);
        	locations.add(l);
        }
		
		@Override
        public void updateGPSLocation(LatLon l) {
        		System.out.println("Order " + orderName + " GPS Location " + l);
        		gpsLocations.add(l);
        		gpsLocation = l;
        }
        
        @Override
        public String getState() {
            return lastState;
        }
        
        @Override
        public LatLon getOrderLocation() {
            return startLocation;
        }
        
        @Override
        public LatLon getDeliveryLocation() {
            return deliveryLocation;
        }

		
       
    }
    
// Should we use activities or local activities for the steps? Local activities should only last a few seconds (why?)
// charging can take more time than this? Do any steps need retrying?
    
public static class DroneWorkflowImpl implements DroneWorkflow {
    	
    	String droneName = "";
    	String state = "";
    	String location = "";	// String name of location, not lat/lon GPS location
    	double charge = 100.0; 	// percentage charge from 0 to 100
    	String orderID = "";
    	OrderWorkflow orderWorkflow = null;

    	
    	ArrayList<String> updates = new ArrayList<String>();
    	
    	// only allow 1 order now
    	ArrayList<OrderWorkflow> orders = new ArrayList<OrderWorkflow>();
    	
    	ArrayList<String> locations = new ArrayList<String>();
    	ArrayList<LatLon> gpsLocations = new ArrayList<LatLon>(); // new GPS locations sent by activity
    	// String gpsLocation = ""; // last gps location
    	LatLon gpsLocation = baseLocation;
    	LatLon nextGPSLocation = null;
    	
    	// Flight Plan
    	LatLon planStart = baseLocation;
    	LatLon planOrder = null;
    	LatLon planDelivery = null;
    	LatLon planEnd = baseLocation;
    	
    	private DroneActivities activities = null;
    	
    	public DroneWorkflowImpl() {
            this.activities = Workflow.newActivityStub(DroneActivities.class);
        }
    	
        @Override
        public String startWorkflow(String name) {
        	
        	System.out.println("Started Drone workflow " + name + ", ID=" + Workflow.getWorkflowInfo().getWorkflowId());
        	
	        // Drones always start at the base location
        	location = "base";
        	locations.add(location);
        	
        	// Charging at end of WF, assume drones start at 100% charge
        	
            System.out.println("Drone " + name + " is ready at base");
        	charge = 100.0;
        	state = "ready";
        	updates.add(state);
        	
            // STEP 1 get order from Kafka
                        
            System.out.println("Drone " + name + " is waiting for an order...");
            orderID = activities.waitForOrder(name);
            System.out.println("Drone " + name + " got an order from Kafka + " + orderID);
            
            
            // Get the Order WF so we can send location and state updates to it
            try {
            	OrderWorkflow orderWorkflow = workflowClient.newWorkflowStub(OrderWorkflow.class, orderID);   
            	orderWorkflow.signalOrder("droneHasOrder");
            }
            catch (Exception e)
            {
            	System.out.println("Expected order workflow to be running but it's not, giving up!");
            	return "order workflow not running giving up";
            }
            System.out.println("Drone " + name + " has got order " + orderID);
            
            // STEP 2 generate "flight plan" - i.e. get the order and delivery locations from the Order
            LatLon orderLoc = orderWorkflow.getOrderLocation();
            LatLon deliveryLoc = orderWorkflow.getDeliveryLocation();
            
            // Generate "Flight plan"
            // Leg 1 is from planStart to Order
            planOrder = orderLoc;
            // set nextGPSLocation with the next location to fly to
            nextGPSLocation = planOrder;
            // Leg 2 is from order to delivery locations
            planDelivery = deliveryLoc;
            // Leg 3 is from delivery location back to start, planEnd is already set to planStart


            System.out.println("Drone " + name + " has generated a flight plan based on Order and Delivery locations");
            System.out.println("Start " + planStart.toString());
            System.out.println("Order " + planOrder.toString());
            System.out.println("Delivery " + planDelivery.toString());
            System.out.println("End " + planEnd.toString());

            double distance = 0;
            double distanceToDelivery = 0;
            double distanceToOrder = 0;
            distance += DroneMaths.distance(planStart, planOrder);
            distanceToOrder = distance;
            distance += DroneMaths.distance(planOrder, planDelivery);
            distanceToDelivery = distance;
            distance += DroneMaths.distance(planDelivery, planEnd);

            System.out.println("Drone " + name + " flight plan total distance (km) = " + distance);
            System.out.println("Drone " + name + " estimated total flight time (h) = " + distance/droneSpeed);
            System.out.println("Drone " + name + " distance to order (km) = " + distanceToOrder);
            System.out.println("Drone " + name + " estimated time until order pickup (h) = " + distanceToOrder/droneSpeed);
            System.out.println("Drone " + name + " distance to delivery (km) = " + distanceToDelivery);
            System.out.println("Drone " + name + " estimated time until delivery (h) = " + distanceToDelivery/droneSpeed);

            // STEP 3 - a real activity - flying to get the order 
            state = "flyingToOrder";
            updates.add(state);
            orderWorkflow.signalOrder("droneOnWayForPickup");
            
            // an actual activity, this is designed to that if it fails it restarts, and picks up the current location
            // rather than the planned start location, as it may have moved some of the distance already.
            // drone location and charge are automatically updated by the activity
            activities.nextLeg(planStart, planOrder, false, null);
            
            // STEP 4 - arrived at order location, collect order - TODO split into notify order of drone arrival and wait for collection?
            // I.e. could fail...
            location = "orderLocation";
        	locations.add(location);
        	state = "pickingUpOrder";
            updates.add(state);
            // picking up takes some time
            System.out.println("Drone + " + name + " picking up Order!");
            Workflow.sleep((int)(pickupTime * 1000 * timeScale));
            System.out.println("Drone + " + name + " picked up Order!");
            orderWorkflow.signalOrder("pickedUpByDrone");
            orderWorkflow.updateLocation(location);
            // we assume that the drone is still using power while picking up the order
            updateCharge(pickupTime);
            
            // STEP 5 - flying to deliver the order            
            state = "startedDelivery";
            nextGPSLocation = planDelivery;
            updates.add(state);
            orderWorkflow.signalOrder("outForDelivery");
            orderWorkflow.updateLocation("onWay");
            System.out.println("Drone + " + name + " delivering Order...");
            // drone flies to delivery location, updating drone and order locations and drone charge as it goes.
            // Blows up here now!
            
            activities.nextLeg(planOrder, planDelivery, true, orderID);
            
            // STEP 6 - drop order
            location = "deliveryLocation";
            locations.add(location);
            state = "droppingOrder";
            updates.add(state);	
            System.out.println("Drone + " + name + " dropping Order!");
            // dropping off takes some time
            Workflow.sleep((int)(deliverTime * 1000 * timeScale));
            System.out.println("Drone + " + name + " dropped Order!");
            orderWorkflow.updateLocation(location);
            orderWorkflow.signalOrder("delivered");
            // we assume that the drone is still using power while dropping the order
            updateCharge(deliverTime);
            
            // Step 7 - return to base
            nextGPSLocation = planEnd;
            System.out.println("Drone + " + name + " returning to Base!");
            state = "returningToBase";
            updates.add(state);
            // fly back to base, update drone location and charge, but not order location as it's been delivered.
            activities.nextLeg(planDelivery, planEnd, false, null);
            
            // STEP 8 - back at base
            location = "base";
            locations.add(location);
            state = "backAtBase";
            
            // STEP 9 - check order
            state = "orderChecking";
            updates.add(state);
            
            if (!orderWorkflow.getState().equals("delivered"))	
            {
            	System.out.println("Undelivered order = " + orderID);
            	// Perform some compensating action
            }
            else
            	orderWorkflow.signalOrder("orderComplete"); // this terminates the order WF 
            
            // Step 10 - delivery complete
            state = "DroneDeliveryCompleted";
            updates.add(state);
            System.out.println("Drone delivery + " + name + " ended.");
            
            // Step 11 - charge
            state = "charging";
            updates.add(state);
            int chargingTime = (int)((1-(charge/100.0)) * maxChargeTime);
            System.out.println("Drone charging! charging time = " + chargingTime + "s");
            Workflow.sleep((int) (1000 * chargingTime * timeScale));
            charge = 100.0;
            state = "charged";
            updates.add(state);
            
            System.out.println(">>>>>>>>> Starting new Drone delivery WF with coninueAsnew with same WF ID!");
            
            // TODO How to start a new WF from here? Easy continue as new https://cadenceworkflow.io/docs/java-client/continue-as-new/
           // if (numDroneDeliveries > 1)
            // Tested works ok with multiple orders on kafka new orders topic
            // Workflow.continueAsNew(name);
            
            // never reached?	
        	return "Drone Delivery Workflow " + name + " completed!";	
        }
        
        @Override
        public void signalOrder(String s) {
        	state = s;
        }
        
        // dummy signal method so Activity can check if WF is alive still
        @Override
        public void alive() {
        }
        
		@Override
        public void updateGPSLocation(LatLon loc) {
        	System.out.println("Drone gps location update " + loc);
        	gpsLocations.add(loc);
        	gpsLocation = loc;
        }
		
		@Override
        public void updateCharge(double time) {
        	// given flying time, update remaining Charge
			double chargeUsed = (time/maxFlightTime) * 100.0;
			charge -= chargeUsed;
			if (charge < 0.0)
				charge = 0.0;
			System.out.println("Drone charge now = " + charge + "%, last used = " + chargeUsed);
        }
        
        @Override
        public String getState() {
            return state;
        }
        
        @Override
        public LatLon getLatLon() {
            return gpsLocation;
        }
        
        @Override
        public LatLon getNextLatLon() {
            return nextGPSLocation;
        }

		
       
    }
    
    // Note we have to get activity info from Activity not Worker https://cadenceworkflow.io/docs/java-client/implementing-activities/#accessing-activity-info
    // something wrong, not getting Hello!!! Only goodbye.
    public static class OrderActivitiesImpl implements OrderActivities
    {
    	 // This would just be sync, once kafka producer returns then proceed with next thing to do 
    	 // can we use Kafka record header meta data for workflowId perhaps? 
         public String sendKafka(String name) {
        	 
        	 
        	 return "done"; 
         }  
         
         // new activity which sends a message to Kafka to notify Drone to come and get it
         public String readyForDelivery(String name) {
        	 
        	 String id = Activity.getWorkflowExecution().getWorkflowId();
        	 System.out.println("Order WF readyForDelivery activity " + name + " id " + id);
        	
        	 // topic, key, value, all Strings
        	 ProducerRecord<String, String> producerRecord = new ProducerRecord<>(orderjobsTopicName, "", id);

             try (KafkaProducer<String, String> producer = new KafkaProducer<>(kafkaProps)) {
                 producer.send(producerRecord);
                 producer.flush();
             } catch (Exception e) {
                 e.printStackTrace();
             }
             
        	 
        	 return "done"; 
         }  
         
    }
    
    // not sure we need activities in Drone or not?
    public static class DroneActivitiesImpl implements DroneActivities
    {
    	
    	private int count = 0;
    	 // This would just be sync, once kafka producer returns then proceed with next thing to do 
    	 // can we use Kafka record header meta data for workflowId perhaps? 
         public String createOrders(String name) {
        	return "done"; 
          
         }
         
         public String waitForOrder(String name) {
        	// String OrderName = "";
        	 
        	 // Kafka consumer that polls for a new Order that's been created and is ready for pickup to trigger Drone delivery trip
        	 // Each Drone can only have 1 order at a time, and each order can only be delivered by 1 drone (or drone wars may result)
        	 // However, if something goes wrong with drone and it cannot pickup the order, then it should put the order back into the Kafka topic for redelivery perhaps and increment a pickup attempts count in Order WF.
        	 Properties kafkaProps = new Properties();

        	 // where should this be?
             try (FileReader fileReader = new FileReader("consumer2.properties")) {
                 kafkaProps.load(fileReader);
             } catch (IOException e) {
                 e.printStackTrace();
             }
             
             // TODO We only want 1 order at a time, how to configure for 1 only?!
             // set max.poll.records to 1 https://kafka.apache.org/10/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html
             // All consumers waiting for order are in a new consumer group
             // NOTE that this means we need partitions >= number of Drones - assumption is this is < 100 for performance reasons
             // What if millions? Then need to do something different
             kafkaProps.put("group.id", "waitForOrder");
             kafkaProps.put("max.poll.records", "1");

             //boolean gotOrder = false;
             
             try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(kafkaProps)) {
                 consumer.subscribe(Collections.singleton(orderjobsTopicName));

                 while (true) {
                	 
                     ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                     for (ConsumerRecord<String, String> record : records) {
                         System.out.print("waitForOrder got an order! ");
                         System.out.println(String.format("topic = %s, partition = %s, offset = %d, key = %s, value = %s",
                                 record.topic(), record.partition(), record.offset(), record.key(), record.value()));
                         // ensure that we don't get this order again
                         consumer.commitAsync();
                         return record.value().toString();
                     }
                 }
             }
             catch (Exception e)
             {
            	 e.printStackTrace();
             }
			return "";
         }
         	
         
         // new method to compute location from A to B and fly
         // TODO What if it fails and is restarted? Need to set start to current location?
         // And need to update Drone location each time we move
         // Example method retry https://github.com/uber/cadence-java-samples/blob/master/src/main/java/com/uber/cadence/samples/hello/HelloActivityRetry.java
         
         public void nextLeg(LatLon start, LatLon end, boolean updateOrderLocation, String orderID)
         {
        	 System.out.println("nextLeg count = " + count++);
        	
        	 
        	 WorkflowExecution execution = Activity.getWorkflowExecution();
        	 String id = execution.getWorkflowId();   	
			 DroneDeliveryApp3.DroneWorkflow droneWF = workflowClient.newWorkflowStub(DroneWorkflow.class, id);
			 
			 DroneDeliveryApp3.OrderWorkflow orderWF = null;
			 
			 if (updateOrderLocation) 
			 {
				 try {
				 orderWF = workflowClient.newWorkflowStub(OrderWorkflow.class, orderID);
				 }
				 catch (Exception e) { System.out.println("failed to create orderWF!!!"); return; }
			 }
			 
			 LatLon actualLocation = droneWF.getLatLon();
			 System.out.println("Drone WF gpsLocation = " + actualLocation);
			 System.out.println("start loc = " + start);

			 // this version ignores provided start, and uses actual actual!
			 // seems to work ok still, so we don't need to provide start arg at all
			 LatLon here = actualLocation;
        	
        	while (true)
     		{
        		try {
					Thread.sleep((int)(moveTime * 1000 * timeScale));
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					return;
				}
     			//tripTime += time;
     			//charge = initialCharge - ((tripTime/maxFlightTime) * 100.0);
     			
     			LatLon next = DroneMaths.nextPosition(here, end, droneSpeed, moveTime);
     			here = next;
     			System.out.println("Drone flew to new location = " + here.toString());
     			double distance = DroneMaths.distance(here, end);
     			System.out.println("Distance to destination = " + distance + " km");
     			//double dtravelled = distance(here, next);
     			//System.out.println("Distance travelled = " + dtravelled);
     			//System.out.println("Charge = " + charge);
     			
     			// always update Drone location?
     			
     			droneWF.updateGPSLocation(here);
     			droneWF.updateCharge(moveTime);
     			
     			if (updateOrderLocation)
     				orderWF.updateGPSLocation(here);
     			

     			// TODO What if we overshoot by a lot?! May need to slow down in practice
     			if (end.sameLocation(here)) // within 1 m
     			{
     				//speed = 0;
     				//arrived = true;
     				//System.out.println("Arrived! tripTime = " + tripTime + " s");
     				System.out.println("Drone arrived at destination.");
     				//System.out.println("Charge = " + charge);
     				return;
     			}
     			
     			 if (count == 1)
            	 {
            		 System.out.println("nextLeg failed on count 1 - retry");
            		 throw new IllegalStateException("nextLeg failed on count 1 - retry");
            	 }
     		}

         }
         
         // Problem with ArrayList? Docs: The only requirement is that activity method arguments and return values are serializable to a byte array using the provided DataConverter (opens new window)interface. The default implementation uses a JSON serializer, but an alternative implementation can be easily configured.
         // Solution? Turn in Order WF Ids ArrayList? 
         // this method is designed to update locations every 60s
         // public void locationLoop(String name, ArrayList<OrderWorkflow> orders) {
         // Try heartbeat for this: https://cadenceworkflow.io/docs/concepts/activities/#long-running-activities
         // https://www.javadoc.io/static/com.uber.cadence/cadence-client/2.6.3/com/uber/cadence/activity/Activity.html
         
         // this version must generate actual lat/lon locations for each time step and create new route between way points
         // problem now is where is the Drone location actually stored/update?
         // and is the loop or the Drone WF really in "control" of the locatation and movement?
         // Location updates need to be coordinate with the state of the Drone WF somehow
         // Drone WF also now need to change state based on location data! i.e. once it has actually arrived
         // if should change course etc.
         
         public void locationLoop(String name, ArrayList<String> ids) {
        	 System.out.println("locationLoop >>>");
        	 WorkflowExecution execution = Activity.getWorkflowExecution();
        	 // ForkJoinPool.commonPool().execute(() -> locationLoopAsync(execution, orders));
        	 
        	 // Turn off until we can work out if WF has completed or not!
        	 
        	 try {
        	 
        	 ForkJoinPool.commonPool().execute(() -> locationLoopAsync(execution, ids)); 
        	 } catch (Exception e)
        	 {
        		 return;
        	 }
         }
         
       //    private void locationLoopAsync(WorkflowExecution execution, ArrayList<OrderWorkflow> orders)
         private void locationLoopAsync(WorkflowExecution execution, ArrayList<String> ids)
         {
        	 
        	 ArrayList<OrderWorkflow> stubs = new ArrayList<OrderWorkflow>();

        	 DroneDeliveryApp3.OrderWorkflow stub;
        	 
        	 for (int i=0; i < numOrders; i++)
        	 {
        		 stub = workflowClient.newWorkflowStub(OrderWorkflow.class, ids.get(i));
        		 stubs.add(stub);
        	 }
        	 
        	 // TODO What does WorkflowExecution tell us?
        	// String id = execution.getWorkflowId();   	
        	// DroneDeliveryApp1.DroneWorkflow stub = workflowClient.newWorkflowStub(DroneWorkflow.class, id);
        	 
        	 
        	 // BUG TODO How do we know if the calling WF has exited or not???????
        	 int x = 0;
        	 while (true)
        	 {
        		 // I'm still alive! Otherwise WF may kill me :-(
        		 // Basically this will throw an error if the WF isn't alive anymore - should catch and exit?
        		 // Doesn't work, throws immediate error as can't use Activity here?! Not an Activity method I guess.
        		 /*
        		 try {
        			 Activity.heartbeat(null);
        		 } catch (Exception e)
        		 {
        			 System.out.println(e + "Heart beat failed so assume WF has completed goodbye from locationLoop!");
        			 return;
        		 }
        		 */
        		 
        		 
        		 // what is returned if the workflow is not running? null perhaps?
        		 /*
        		 if (stub == null)
        		 {
        			 System.out.println("Assume WF has ended giving up on locationLoop!");
        			 return;
        		 }
        		 
        		  */
        		 
        		 // Hack to check if WF is alive or not
        		 
        		 /*
        		 try 
        		 { stub.alive(); }
        		 catch (Exception e)
        		 {
        			 // Odd - never reached why?!
        			 System.out.println(e + " locationLoop terminating as WF has exited!");
        		 }
        		 */
        		 
        		 
        		 // get current Drone location - how?! query the parent WF?
        		 // update location on all Orders in Drone WF - where do we get the list from?
        		 // Can't get Execution from different thread!
        		 //WorkflowExecution execution = Activity.getWorkflowExecution();
        		// System.out.println("locationLoop says hello from WF " + execution.getWorkflowId());
        		 x++;
        		// System.out.println("location Loop says Hello from " + execution.getWorkflowId());
        		 
        		 // Hack - get stubs again each time
        		 /*
        		 stubs = new ArrayList<OrderWorkflow>();
        		 for (int i=0; i < numOrders; i++)
            	 {
        			 try {
            		 stub = workflowClient.newWorkflowStub(OrderWorkflow.class, ids.get(i));
            		 stubs.add(stub);
        			 } catch (Exception e)
        			 {
        				 System.out.println("WF ID not found setting stub to null");
        				 stubs.add(null);
        			 }
            	 }
            	 */
        		 
        		 // now also send gps location to parent Drone WF
        		 String y = "lat/lon " + x;
        		 
        		 try {
        			 String id = execution.getWorkflowId();   	
        			 DroneDeliveryApp3.DroneWorkflow stub2 = workflowClient.newWorkflowStub(DroneWorkflow.class, id);
        			 String droneState = stub2.getState();
        			 LatLon currentLocation = stub2.getLatLon();
        			 LatLon nextLocation = stub2.getNextLatLon();
        			 System.out.println("Drone state = " + droneState);
        			 
        			 // what's the logic? If one way to order location then plot locations from start to delivery
        			 if (droneState.equals(""))
        			 {
        				 
        			 }
        			// stub2.updateGPSLocation(y);
        		 } catch (Exception e)
        		 {
        			 System.out.println(x + " locationLoop - no WF for Drone!");
        			 return;
        		 }
        		 
        		 for (int j=0; j < numOrders; j++)
                 {
        			 // System.out.println("locationLoop order state = " + stubs.get(j).getState());
        			 // This try/catch now works ok to stop run-time errors due to orders WF being terminated,
        			 // but the loop still runs and restarts the main WF - how to exit from it?!
        			 // return now works ok but not very elegant!
        			 
        			 try {
        			//  if (stubs.get(j) != null && !stubs.get(j).getState().equals("delivered"))
        				 // put location update conditions into Order instead
        				// if (stubs.get(j) != null)
        				 //stubs.get(j).updateGPSLocation(y);
        			 } catch (Exception e) { System.out.println(x + " locationLoop - no WF for order!"); return;}
        			 // System.out.println("location_" + x);
                 }
        		 
        		 try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					return;
				}
        	 }
        	 
         	// return "done"; 
        	
              
          }

		
    }

    // from here https://github.com/uber/cadence-java-samples/blob/master/src/main/java/com/uber/cadence/samples/common/RegisterDomain.java
    public static void registerDomain(String domain)
    {   	
    	String name = "";
    	
    	// IWorkflowService cadenceService = new WorkflowServiceTChannel(ClientOptions.defaultInstance());
    	IWorkflowService cadenceService = new WorkflowServiceTChannel(ClientOptions.newBuilder().setHost(host).setPort(7933).build());
        RegisterDomainRequest request = new RegisterDomainRequest();
        request.setDescription(name);
        request.setEmitMetric(false);
        request.setName(domain);
        int retentionPeriodInDays = 1;
        request.setWorkflowExecutionRetentionPeriodInDays(retentionPeriodInDays);
        try {
          cadenceService.RegisterDomain(request);
          System.out.println(
              "Successfully registered domain \""
                  + domain
                  + "\" with retentionDays="
                  + retentionPeriodInDays);
        } catch (DomainAlreadyExistsError e) {
          System.out.println("Domain \"" + domain + "\" is already registered");
        } catch (BadRequestError e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ServiceBusyError e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClientVersionNotSupportedError e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
    
    public static void main(String[] args) {
    	
    	System.out.println("Clearing topic, send a new order request to trigger things!");
    	clearTopic();
    	// System.exit(0);
    	
    	System.out.println("registerDomain(" + domainName + ")");
    	registerDomain(domainName);
    	
    	
    	
    	
        // To link the workflow implementation to the Cadence framework, it should be
        // registered with a worker that connects to a Cadence Service.
        workflowClient =
                WorkflowClient.newInstance(
                        new WorkflowServiceTChannel(ClientOptions.newBuilder().setHost(host).setPort(7933).build()),
                        WorkflowClientOptions.newBuilder().setDomain(domainName).build());

        // Get worker to poll the task list.
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(orderActivityName);
        Worker worker2 = factory.newWorker(droneActivityName);


        // Workflows are stateful. So you need a type to create instances.
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker2.registerWorkflowImplementationTypes(DroneWorkflowImpl.class);

        
        worker.registerActivitiesImplementations(new OrderActivitiesImpl());
        worker2.registerActivitiesImplementations(new DroneActivitiesImpl());
        
        // Start listening to the workflow and activity task lists.
        factory.start();
        
        kafkaProps = new Properties();

        try (FileReader fileReader = new FileReader("producer2.properties")) {
            kafkaProps.load(fileReader);
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        // TODO Do we need workers for Drone workflows as well?????
        
        ArrayList<DroneWorkflow> drones = new ArrayList<DroneWorkflow>();
        
        // start some Drone workflows
        int numDrones = 1;
        for (int i=0; i < numDrones; i++)
        {
        	DroneWorkflow droneWorkflow = workflowClient.newWorkflowStub(DroneWorkflow.class);
            
        	WorkflowExecution workflowExecution = WorkflowClient.start(droneWorkflow::startWorkflow, "Drone_" + i);
        	drones.add(droneWorkflow);
        }

        
        	
     
        
        // Have to wait until all finished otherwise there won't be any worker threads running!
        // Should do a proper wait on end of the flows I guess.
        try {
			Thread.sleep(600000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


        
        System.exit(0);
    }
    
    public static void clearTopic() {
    	// String OrderName = "";
    	 
    	 // Kafka consumer that polls for a new Order that's been created and is ready for pickup to trigger Drone delivery trip
    	 // Each Drone can only have 1 order at a time, and each order can only be delivered by 1 drone (or drone wars may result)
    	 // However, if something goes wrong with drone and it cannot pickup the order, then it should put the order back into the Kafka topic for redelivery perhaps and increment a pickup attempts count in Order WF.
    	 Properties kafkaProps = new Properties();

    	 // where should this be?
         try (FileReader fileReader = new FileReader("consumer2.properties")) {
             kafkaProps.load(fileReader);
         } catch (IOException e) {
             e.printStackTrace();
         }
         
         // TODO We only want 1 order at a time, how to configure for 1 only?!
         // set max.poll.records to 1 https://kafka.apache.org/10/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html
         // All consumers waiting for order are in a new consumer group
         // NOTE that this means we need partitions >= number of Drones - assumption is this is < 100 for performance reasons
         // What if millions? Then need to do something different
         kafkaProps.put("group.id", "waitForOrder");
         // kafkaProps.put("max.poll.records", "1");

         //boolean gotOrder = false;
         
         try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(kafkaProps)) {
             consumer.subscribe(Collections.singleton(orderjobsTopicName));

             while (true) {
            	 
                 ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                 for (ConsumerRecord<String, String> record : records) {
                     System.out.print("waitForOrder got an order! ");
                     System.out.println(String.format("topic = %s, partition = %s, offset = %d, key = %s, value = %s",
                             record.topic(), record.partition(), record.offset(), record.key(), record.value()));
                     // ensure that we don't get this order again
                    
                     
                 }
                 consumer.commitAsync();
                 return;
             }
         }
         catch (Exception e)
         {
        	 e.printStackTrace();
         }
		return;
     }
}