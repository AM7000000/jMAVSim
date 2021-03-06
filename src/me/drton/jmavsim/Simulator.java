package me.drton.jmavsim;

import me.drton.jmavlib.geo.LatLonAlt;
import me.drton.jmavlib.mavlink.MAVLinkSchema;
import me.drton.jmavsim.vehicle.AbstractMulticopter;
import me.drton.jmavsim.vehicle.AbstractVehicle;
import me.drton.jmavsim.vehicle.Quadcopter;
import org.xml.sax.SAXException;

import javax.vecmath.Matrix3d;
import javax.vecmath.Vector3d;
import java.lang.Math;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

/**
 * User: ton Date: 26.11.13 Time: 12:33
 */
public class Simulator implements Runnable {

    public static boolean USE_SERIAL_PORT = false;
    public static boolean COMMUNICATE_WITH_QGC = true;
    public static final int DEFAULT_AUTOPILOT_PORT = 14560;
    public static final int DEFAULT_QGC_BIND_PORT = 0;
    public static final int DEFAULT_QGC_PEER_PORT = 14550;
    public static final String DEFAULT_SERIAL_PATH = "/dev/tty.usbmodem1";
    public static final int DEFAULT_SERIAL_BAUD_RATE = 230400;
    public static final String LOCAL_HOST = "127.0.0.1";

    private static String autopilotIpAddress = LOCAL_HOST;
    private static int autopilotPort = DEFAULT_AUTOPILOT_PORT;
    private static String qgcIpAddress = LOCAL_HOST;
    private static int qgcBindPort = DEFAULT_QGC_BIND_PORT;
    private static int qgcPeerPort = DEFAULT_QGC_PEER_PORT;
    private static String serialPath = DEFAULT_SERIAL_PATH;
    private static int serialBaudRate = DEFAULT_SERIAL_BAUD_RATE;

    private static HashSet<Integer> monitorMessageIds = new HashSet<Integer>();
    private static boolean monitorMessage = false;

    Runnable keyboardWatcher;
    Visualizer3D visualizer;
    AbstractMulticopter vehicle;
    CameraGimbal2D gimbal;

    private World world;
    private int sleepInterval = 2;  // Main loop interval, in ms
    private int simDelayMax = 10;  // Max delay between simulated and real time to skip samples in simulator, in ms
    private ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private boolean shutdown = false;

    public Simulator() throws IOException, InterruptedException, ParserConfigurationException, SAXException {
        // Create world
        world = new World();
        // Set global reference point
        // Zurich Irchel Park: 47.397742, 8.545594, 488m
        // Seattle downtown (15 deg declination): 47.592182, -122.316031, 86m
        // Moscow downtown: 55.753395, 37.625427, 155m
        LatLonAlt referencePos = new LatLonAlt(47.397742, 8.545594, 488.0);
        world.setGlobalReference(referencePos);

        MAVLinkSchema schema = new MAVLinkSchema("mavlink/message_definitions/common.xml");

        // Create MAVLink connections
        MAVLinkConnection connHIL = new MAVLinkConnection(world);
        world.addObject(connHIL);
        MAVLinkConnection connCommon = new MAVLinkConnection(world);
        // Don't spam ground station with HIL messages
        connCommon.addSkipMessage(schema.getMessageDefinition("HIL_CONTROLS").id);
        connCommon.addSkipMessage(schema.getMessageDefinition("HIL_SENSOR").id);
        connCommon.addSkipMessage(schema.getMessageDefinition("HIL_GPS").id);
        world.addObject(connCommon);

        // Create ports
        MAVLinkPort autopilotMavLinkPort;
        if (USE_SERIAL_PORT) {
            //Serial port: connection to autopilot over serial.
            SerialMAVLinkPort port = new SerialMAVLinkPort(schema);
            port.setup(serialPath, serialBaudRate, 8, 1, 0);
            autopilotMavLinkPort = port;
        } else {
            UDPMavLinkPort port = new UDPMavLinkPort(schema);
            //port.setDebug(true);
            port.setup(0, autopilotIpAddress, autopilotPort); // default source port 0 for autopilot, which is a client of JMAVSim
            // monitor certain mavlink messages.
            if (monitorMessage)  port.setMonitorMessageID(monitorMessageIds);
            autopilotMavLinkPort = port;
        }

        // allow HIL and GCS to talk to this port
        connHIL.addNode(autopilotMavLinkPort);
        connCommon.addNode(autopilotMavLinkPort);
        // UDP port: connection to ground station
        UDPMavLinkPort udpGCMavLinkPort = new UDPMavLinkPort(schema);
        //udpGCMavLinkPort.setDebug(true);
        if (COMMUNICATE_WITH_QGC) {
            udpGCMavLinkPort.setup(qgcBindPort, qgcIpAddress, qgcPeerPort);
            connCommon.addNode(udpGCMavLinkPort);
        }

        // Create environment
        SimpleEnvironment simpleEnvironment = new SimpleEnvironment(world);

        // Mag vector in earth field, loosely based on the earth field in Zurich,
        // but without declination. The declination will be added below based on
        // the origin GPS position.
        Vector3d magField = new Vector3d(0.21523, 0.0f, 0.42741);

        //simpleEnvironment.setWind(new Vector3d(0.0, 5.0, 0.0));
        simpleEnvironment.setGroundLevel(0.0f);
        world.addObject(simpleEnvironment);

        // Set declination based on the initialization position of the Simulator
        // getMagDeclination() is in degrees, GPS position is in radians and the result
        // variable decl is back in radians.
        double decl = (world.getEnvironment().getMagDeclination(referencePos.lat / Math.PI * 180.0, referencePos.lon / Math.PI * 180.0) / 180.0) * Math.PI;

        Matrix3d magDecl = new Matrix3d();
        magDecl.rotZ(decl);
        magDecl.transform(magField);
        simpleEnvironment.setMagField(magField);

        // Create vehicle with sensors
        vehicle = buildMulticopter();

        // Create MAVLink HIL system
        // SysId should be the same as autopilot, ComponentId should be different!
        MAVLinkHILSystem hilSystem = new MAVLinkHILSystem(schema, 1, 51, vehicle);
        connHIL.addNode(hilSystem);
        world.addObject(vehicle);

        // Create 3D visualizer
        visualizer = new Visualizer3D(world);

        setFPV();

        // Put camera on vehicle with gimbal
        gimbal = buildGimbal();
        world.addObject(gimbal);

        // Open ports
        autopilotMavLinkPort.open();

        if (autopilotMavLinkPort instanceof SerialMAVLinkPort) {
            // Special handling for PX4: Start MAVLink instance
            SerialMAVLinkPort port = (SerialMAVLinkPort) autopilotMavLinkPort;
            port.sendRaw("\nsh /etc/init.d/rc.usb\n".getBytes());
        }

        if (COMMUNICATE_WITH_QGC) {
            udpGCMavLinkPort.open();
        }


        keyboardWatcher = new Runnable() {
            InputStreamReader fileInputStream = new InputStreamReader(System.in);
            BufferedReader bufferedReader = new BufferedReader(fileInputStream);
            @Override
            public void run() {

                try {
                    // get user input as a String
                    String input = "";
                    if (bufferedReader.ready()) {
                        input = bufferedReader.readLine();
                    }
                    if(input.equals("f")) {
                        setFPV();
                    } else if (input.equals("g")) {
                        setGimbal();
                    } else if (input.equals("s")) {
                        setStaticCamera();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        executor.scheduleAtFixedRate(keyboardWatcher, 0, 1, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this, 0, sleepInterval, TimeUnit.MILLISECONDS);

        while(!shutdown) Thread.sleep(1000);

        // Close ports
        autopilotMavLinkPort.close();
        udpGCMavLinkPort.close();
    }

    private AbstractMulticopter buildMulticopter() throws IOException {
        Vector3d gc = new Vector3d(0.0, 0.0, 0.0);  // gravity center
        AbstractMulticopter vehicle = new Quadcopter(world, "models/3dr_arducopter_quad_x.obj", "x", 0.33 / 2, 4.0,
                0.05, 0.005, gc);
        vehicle.setMass(0.8);
        Matrix3d I = new Matrix3d();
        // Moments of inertia
        I.m00 = 0.005;  // X
        I.m11 = 0.005;  // Y
        I.m22 = 0.009;  // Z
        vehicle.setMomentOfInertia(I);
        SimpleSensors sensors = new SimpleSensors();
        sensors.setGPSDelay(200);
        sensors.setGPSStartTime(System.currentTimeMillis() + 1000);
        vehicle.setSensors(sensors);
        vehicle.setDragMove(0.02);
        //v.setDragRotate(0.1);

        return vehicle;
    }

    private CameraGimbal2D buildGimbal() {
        gimbal = new CameraGimbal2D(world);
        gimbal.setBaseObject(vehicle);
        gimbal.setPitchChannel(4);  // Control gimbal from autopilot
        gimbal.setPitchScale(1.57); // +/- 90deg
        return gimbal;
    }

    private void setFPV() {
        // Put camera on vehicle (FPV)
        visualizer.setViewerPositionObject(vehicle);
        visualizer.setViewerPositionOffset(new Vector3d(-0.6f, 0.0f, -0.3f));   // Offset from vehicle center
    }

    private void setGimbal() {
        visualizer.setViewerPositionOffset(new Vector3d(0.0f, 0.0f, 0.0f));
        visualizer.setViewerPositionObject(gimbal);
    }

    private void setStaticCamera() {
        // Put camera on static point and point to vehicle
        visualizer.setViewerPosition(new Vector3d(-5.0, 0.0, -1.7));
        visualizer.setViewerTargetObject(vehicle);
    }

    public void run() {
        try {
            //keyboardWatcher.run();
            long t = System.currentTimeMillis();
            world.update(t);
        }
        catch (Exception e) {
            executor.shutdown();
        }
    }

    public final static String PRINT_INDICATION_STRING = "-m <comma-separated list of mavlink message IDs to monitor. If none are listed, all messages will be monitored.>";
    public final static String UDP_STRING = "-udp <autopilot ip address>:<autopilot port>";
    public final static String QGC_STRING = "-qgc <qgc ip address>:<qgc peer port> <qgc bind port>";
    public final static String SERIAL_STRING = "-serial <path> <baudRate>";
    public final static String USAGE_STRING = "java -cp lib/*:out/production/jmavsim.jar me.drton.jmavsim.Simulator " +
            "[" + UDP_STRING + " | " + SERIAL_STRING + "] "+ QGC_STRING + " " + PRINT_INDICATION_STRING;

    public static void main(String[] args)
            throws InterruptedException, IOException, ParserConfigurationException, SAXException {

        // default is to use UDP.
        if (args.length == 0) {
            USE_SERIAL_PORT = false;
        }
        if (args.length > 8) {
            System.err.println("Incorrect number of arguments. \n Usage: " + USAGE_STRING);
            return;
        }

        int i = 0;
        while (i < args.length) {
            String arg = args[i++];
            if (arg.equalsIgnoreCase("--help")) {
                handleHelpFlag();
                return;
            }
            if (arg.equalsIgnoreCase("-m")) {
                monitorMessage = true;
                if (i < args.length) {
                    String nextArg = args[i++];
                    try {
                        if (nextArg.startsWith("-")) {
                            // if user ONLY passes in -m, monitor all messages.
                            continue;
                        }
                        if (!nextArg.contains(",")) {
                            monitorMessageIds.add(Integer.parseInt(nextArg));
                        }
                        String split[] = nextArg.split(",");
                        for (String s : split) {
                            monitorMessageIds.add(Integer.parseInt(s));
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Expected: " + PRINT_INDICATION_STRING + ", got: " + Arrays.toString(args));
                        return;
                    }
                } else {
                    // if user ONLY passes in -m, monitor all messages.
                    continue;
                }
            }
            else if (arg.equalsIgnoreCase("-udp")) {
                USE_SERIAL_PORT = false;
                if (i == args.length) {
                    // only arg is -udp, so use default values.
                    break;
                }
                if (i < args.length) {
                    String nextArg = args[i++];
                    if (nextArg.startsWith("-")) {
                        // only turning on udp, but want to use default ports
                        i--;
                        continue;
                    }
                    try {
                        // try to parse passed-in ports.
                        String[] list = nextArg.split(":");
                        if (list.length != 2) {
                            System.err.println("Expected: " + UDP_STRING + ", got: " + Arrays.toString(list));
                            return;
                        }
                        autopilotIpAddress = list[0];
                        autopilotPort = Integer.parseInt(list[1]);
                    } catch (NumberFormatException e) {
                        System.err.println("Expected: " + USAGE_STRING + ", got: " + e.toString());
                        return;
                    }
                } else {
                    System.err.println("-udp needs an argument: " + UDP_STRING);
                    return;
                }
            } else if (arg.equals("-serial")) {
                USE_SERIAL_PORT = true;
                if (i == args.length) {
                    // only arg is -serial, so use default values
                    break;
                }
                if ( (i+2) <= args.length) {
                    try {
                        serialPath = args[i++];
                        serialBaudRate = Integer.parseInt(args[i++]);
                    } catch (NumberFormatException e) {
                        System.err.println("Expected: " + USAGE_STRING + ", got: " + e.toString());
                        return;
                    }
                } else {
                    System.err.println("-serial needs two arguments. Expected: " + SERIAL_STRING + ", got: " + Arrays.toString(args));
                    return;
                }
            } else if (arg.equals("-qgc")) {
                COMMUNICATE_WITH_QGC = true;
                // if (i < args.length) {
                //     String firstArg = args[i++];
                //     try {
                //         String[] list = firstArg.split(":");
                //         if (list.length == 1) {
                //             // Only one argument turns off QGC if the arg is -1
                //             //qgcBindPort = Integer.parseInt(list[0]);
                //             if (qgcBindPort < 0) {
                //                 COMMUNICATE_WITH_QGC = false;
                //                 continue;
                //             } else {
                //                 System.err.println("Expected: " + QGC_STRING + ", got: " + Arrays.toString(args));
                //                 return;
                //             }
                //         } else if (list.length == 2) {
                //             qgcIpAddress = list[0];
                //             qgcPeerPort = Integer.parseInt(list[1]);
                //         } else {
                //             System.err.println("-qgc needs the correct number of arguments. Expected: " + QGC_STRING + ", got: " + Arrays.toString(args));
                //             return;
                //         }
                //         if (i < args.length) {
                //             // Parsed QGC peer IP and peer Port, or errored out already
                //             String secondArg = args[i++];
                //             qgcBindPort = Integer.parseInt(secondArg);
                //         } else {
                //             System.err.println("Wrong number of arguments. Expected: " + QGC_STRING + ", got: " + Arrays.toString(args));
                //         }
                //     } catch (NumberFormatException e) {
                //         System.err.println("Expected: " + USAGE_STRING + ", got: " + e.toString());
                //         return;
                //     }
                // } else {
                //     System.err.println("-qgc needs an argument: " + QGC_STRING);
                //     return;
                // }
            } else {
                System.err.println("Unknown flag: " + arg + ", usage: " + USAGE_STRING);
                return;
            }
        }

        if (i != args.length) {
            System.err.println("Usage: " + USAGE_STRING);
            return;
        } else { System.out.println("Success!"); }

        new Simulator();
    }

    private static void handleHelpFlag() {
        System.out.println("Usage: " + USAGE_STRING);
        System.out.println("\n Note: if <qgc <port> is set to -1, JMavSim won't generate Mavlink messages for GroundControl.");
    }

}
