package com.aaronjwood.portauthority.network;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import com.aaronjwood.portauthority.async.ScanPortsAsyncTask;
import com.aaronjwood.portauthority.async.WolAsyncTask;
import com.aaronjwood.portauthority.db.Database;
import com.aaronjwood.portauthority.response.HostAsyncResponse;

import java.io.IOException;
import java.io.Serializable;

public class Host implements Serializable {

    private String hostname;
    private String ip;
    private String mac;

    /**
     * Constructor to set necessary information without a known hostname
     *
     * @param ip  This host's IP address
     * @param mac This host's MAC address
     */
    public Host(String ip, String mac) {
        this(null, ip, mac);
    }

    /**
     * Constructor to set necessary information with a known hostname
     *
     * @param hostname This host's hostname
     * @param ip       This host's IP address
     * @param mac      This host's MAC address
     */
    public Host(String hostname, String ip, String mac) {
        this.hostname = hostname;
        this.ip = ip;
        this.mac = mac;
    }

    /**
     * Returns this host's hostname
     *
     * @return
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Sets this host's hostname to the given value
     *
     * @param hostname Hostname for this host
     * @return
     */
    public Host setHostname(String hostname) {
        this.hostname = hostname;

        return this;
    }

    /**
     * Returns this host's IP address
     *
     * @return
     */
    public String getIp() {
        return ip;
    }

    /**
     * Returns this host's MAC address
     *
     * @return
     */
    public String getMac() {
        return mac;
    }

    public void wakeOnLan() {
        new WolAsyncTask().execute(mac, ip);
    }

    /**
     * Starts a port scan
     *
     * @param ip        IP address
     * @param startPort The port to start scanning at
     * @param stopPort  The port to stop scanning at
     * @param timeout   Socket timeout
     * @param delegate  Delegate to be called when the port scan has finished
     */
    public static void scanPorts(String ip, int startPort, int stopPort, int timeout, HostAsyncResponse delegate) {
        new ScanPortsAsyncTask(delegate).execute(ip, startPort, stopPort, timeout);
    }

    /**
     * Fetches the MAC vendor from the database
     *
     * @param mac     MAC address
     * @param context Application context
     */
    public static String getMacVendor(String mac, Context context) throws IOException, SQLiteException {
        Database db = new Database(context);
        db.openDatabase("network.db");
        Cursor cursor = db.queryDatabase("SELECT vendor FROM ouis WHERE mac LIKE ?", new String[]{mac});
        String vendor;

        if (cursor.moveToFirst()) {
            //fix  all uppercase vendor's names... and wrong names.. (example: Asiarock-->Asrock)
            vendor = (cursor.getString(cursor.getColumnIndex("vendor")).toLowerCase());
            vendor = vendor.replace("tp-link", "TP-Link");
            vendor = vendor.replace("giga-byte", "Giga-Byte");
            vendor = vendor.replace("ltd", "LTD");
            vendor = vendor.replace("hongkong", "Hongkong");
            vendor = vendor.replace("co.", "CO.");
            vendor = vendor.replace(" corporation", " Corporation");
            vendor = vendor.replace("inc.", "INC.");
            vendor = vendor.replace("computer", "Computer");
            vendor = vendor.replace("corporate", "Corporate");
            vendor = vendor.replace("hon hai", "Hon Hai");
            vendor = vendor.replace("htc", "HTC");
            vendor = vendor.replace("tinno", "Tinno");
            vendor = vendor.replace("asiarock", "Asrock");
            vendor = vendor.replace("incorporation", "Corporation");
            vendor = vendor.substring(0, 1).toUpperCase() + vendor.substring(1); //first letter uppercase
        } else {
            vendor = "Vendor not in database";
            // manually add unknown vendors (September 2017)
            if (mac.equals("00ce39")) vendor += " (Emtec ethernet)";
            if (mac.equals("c6ea1d")) vendor += " (Telecom Italia)";
            if (mac.equals("3ca067")) vendor += " (Liteon TV module)";
            if (mac.equals("d4dccd")) vendor += " (Apple)";
            if (mac.equals("4c74bf")) vendor += " (Apple)";
            if (mac.equals("b4e62a")) vendor += " (LG Innotek)";
            if (mac.equals("a04c5b")) vendor += " (Shenzhen Tinno Mobile)";
            if (mac.equals("58c5cb")) vendor += " (Samsung Electronics Co.,Ltd.)";
            if (mac.equals("6459f8")) vendor += " (Vodafone Modem)";
            if (mac.equals("f8e903")) vendor += " (D-Link International)";
            if (mac.equals("fc1910")) vendor += " (Samsung Electronics Co.,Ltd)";
            if (mac.equals("283f69")) vendor += " (Sony Mobile Communications AB)";
        }

        cursor.close();
        db.close();

        return vendor;
    }

}
