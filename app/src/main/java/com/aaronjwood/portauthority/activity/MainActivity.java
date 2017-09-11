package com.aaronjwood.portauthority.activity;

import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.sqlite.SQLiteException;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AppCompatActivity;
import android.view.ContextMenu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.aaronjwood.portauthority.BuildConfig;
import com.aaronjwood.portauthority.R;
import com.aaronjwood.portauthority.adapter.HostAdapter;
import com.aaronjwood.portauthority.network.Discovery;
import com.aaronjwood.portauthority.network.Host;
import com.aaronjwood.portauthority.network.Wireless;
import com.aaronjwood.portauthority.response.MainAsyncResponse;
import com.aaronjwood.portauthority.utils.Errors;
import com.aaronjwood.portauthority.utils.UserPreference;
import com.squareup.leakcanary.LeakCanary;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class MainActivity extends AppCompatActivity implements MainAsyncResponse {

    private final static int TIMER_INTERVAL = 1500;

    private Wireless wifi;
    private ListView hostList;
    private TextView internalIp;
    private TextView externalIp;
    private String cachedWanIp;
    private TextView signalStrength;
    private TextView ssid;
    private TextView bssid;
    private TextView populate;
    private Button discoverHostsBtn;
    private String discoverHostsStr; // Cache this so it's not looked up every time a host is found.
    private ProgressDialog scanProgressDialog;
    private Handler signalHandler = new Handler();
    private Handler scanHandler;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter = new IntentFilter();
    private HostAdapter hostAdapter;
    private List<Host> hosts = Collections.synchronizedList(new ArrayList<Host>());
    private boolean sortAscending;

    /**
     * Activity created
     *
     * @param savedInstanceState Data from a saved state
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (BuildConfig.DEBUG) {
            LeakCanary.install(getApplication());
        }

        setContentView(R.layout.activity_main);

        internalIp = (TextView) findViewById(R.id.internalIpAddress);
        externalIp = (TextView) findViewById(R.id.externalIpAddress);
        signalStrength = (TextView) findViewById(R.id.signalStrength);
        ssid = (TextView) findViewById(R.id.ssid);
        bssid = (TextView) findViewById(R.id.bssid);
        hostList = (ListView) findViewById(R.id.hostList);
        discoverHostsBtn = (Button) findViewById(R.id.discoverHosts);
        discoverHostsStr = getResources().getString(R.string.hostDiscovery);

        wifi = new Wireless(getApplicationContext());
        scanHandler = new Handler(Looper.getMainLooper());

        populate = (TextView) findViewById(R.id.populate);
        populate.setVisibility(View.GONE);
        
        setupHostsAdapter();
        setupDrawer();
        setupReceivers();
        setupHostDiscovery();
    }

    /**
     * Sets up animations for the activity
     */
    private void setAnimations() {
        LayoutAnimationController animation = AnimationUtils.loadLayoutAnimation(MainActivity.this, R.anim.layout_slide_in_bottom);
        hostList.setLayoutAnimation(animation);
    }

    /**
     * Sets up the adapter to handle discovered hosts
     */
    private void setupHostsAdapter() {
        setAnimations();
        hostAdapter = new HostAdapter(this, hosts);

        hostList.setAdapter(hostAdapter);
        if (!hosts.isEmpty()) {
            discoverHostsBtn.setText(discoverHostsStr + " (" + hosts.size() + ")");
        }
    }

    /**
     * Sets up the device's MAC address and vendor
     */
    private void setupMac() {
        TextView macAddress = (TextView) findViewById(R.id.deviceMacAddress);
        TextView macVendor = (TextView) findViewById(R.id.deviceMacVendor);
        if (!wifi.isEnabled()) {
            macAddress.setText(R.string.wifiDisabled);
            macVendor.setText(R.string.wifiDisabled);

            return;
        }

        try {
            String mac = wifi.getMacAddress();
            String vendor = Host.getMacVendor(mac.replace(":", "").substring(0, 6), this);
            macAddress.setText(mac);
            macVendor.setText(vendor);
        } catch (UnknownHostException | SocketException e) {
            macAddress.setText(R.string.noWifiConnection);
            macVendor.setText(R.string.noWifiConnection);
        } catch (IOException | SQLiteException e) {
            macVendor.setText(R.string.getMacVendorFailed);
        }
    }

    /**
     * Sets up event handlers and functionality for host discovery
     */
    private void setupHostDiscovery() {
        discoverHostsBtn.setOnClickListener(new View.OnClickListener() {

            /**
             * Click handler to perform host discovery
             * @param v
             */
            @Override
            public void onClick(View v) {
                if (!wifi.isEnabled()) {
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.wifiDisabled), Toast.LENGTH_SHORT).show();

                    return;
                }

                if (!wifi.isConnectedWifi()) {
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.notConnectedWifi), Toast.LENGTH_SHORT).show();

                    return;
                }

                discoverHostsBtn.setText("wait...");
                populate.setVisibility(View.VISIBLE);

                setAnimations();

                hosts.clear();
                discoverHostsBtn.setText(discoverHostsStr);
                hostAdapter.notifyDataSetChanged();

                scanProgressDialog = new ProgressDialog(MainActivity.this, R.style.DialogTheme);
                scanProgressDialog.setCancelable(false);
                scanProgressDialog.setTitle(getResources().getString(R.string.hostScan));
                scanProgressDialog.setMessage(String.format(getResources().getString(R.string.subnetHosts), wifi.getNumberOfHostsInWifiSubnet()));
                scanProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                scanProgressDialog.setProgress(0);
                scanProgressDialog.setMax(wifi.getNumberOfHostsInWifiSubnet());
                scanProgressDialog.show();

                try {
                    Integer ip = wifi.getInternalWifiIpAddress(Integer.class);
                    Discovery.scanHosts(ip, wifi.getInternalWifiSubnet(), UserPreference.getHostSocketTimeout(getApplicationContext()), MainActivity.this);
                } catch (UnknownHostException e) {
                    Errors.showError(getApplicationContext(), getResources().getString(R.string.notConnectedWifi));
                }
            }
        });

        hostList.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            /**
             * Click handler to open the host activity for a specific host found on the network
             * @param parent
             * @param view
             * @param position
             * @param id
             */
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Host host = (Host) hostList.getItemAtPosition(position);
                if (host == null) {
                    return;
                }

                Intent intent = new Intent(MainActivity.this, LanHostActivity.class);
                intent.putExtra("HOST", host);
                startActivity(intent);
            }
        });

        registerForContextMenu(hostList);
    }

    /**
     * Inflate our context menu to be used on the host list
     *
     * @param menu
     * @param v
     * @param menuInfo
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        if (v.getId() == R.id.hostList) {
            MenuInflater inflater = getMenuInflater();
            inflater.inflate(R.menu.host_menu, menu);
        }
    }

    /**
     * Handles actions selected from the context menu for a host
     *
     * @param item
     * @return
     */
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        switch (item.getItemId()) {
            case R.id.sortHostname:
                if (sortAscending) {
                    hostAdapter.sort(new Comparator<Host>() {
                        @Override
                        public int compare(Host lhs, Host rhs) {
                            return rhs.getHostname().toLowerCase().compareTo(lhs.getHostname().toLowerCase());
                        }
                    });
                } else {
                    hostAdapter.sort(new Comparator<Host>() {
                        @Override
                        public int compare(Host lhs, Host rhs) {
                            return lhs.getHostname().toLowerCase().compareTo(rhs.getHostname().toLowerCase());
                        }
                    });
                }

                sortAscending = !sortAscending;
                return true;
            case R.id.copyHostname:
                setClip("hostname", hosts.get(info.position).getHostname());

                return true;
            case R.id.copyIp:
                setClip("ip", hosts.get(info.position).getIp());

                return true;
            case R.id.copyMac:
                setClip("mac", hosts.get(info.position).getMac());

                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    /**
     * Sets some text to the system's clipboard
     *
     * @param label Label for the text being set
     * @param text  The text to save to the system's clipboard
     */
    private void setClip(CharSequence label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
    }

    /**
     * Sets up and registers receivers
     */
    private void setupReceivers() {
        receiver = new BroadcastReceiver() {

            /**
             * Detect if a network connection has been lost or established
             * @param context
             * @param intent
             */
            @Override
            public void onReceive(Context context, Intent intent) {
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (info == null) {
                    return;
                }

                getNetworkInfo(info);
            }

        };

        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(receiver, intentFilter);
    }

    /**
     * Gets network information about the device and updates various UI elements
     */
    private void getNetworkInfo(NetworkInfo info) {
        setupMac();
        getExternalIp();

        if (!info.isConnected() || !wifi.isEnabled()) {
            signalHandler.removeCallbacksAndMessages(null);
            internalIp.setText(Wireless.getInternalMobileIpAddress());
        }

        if (!wifi.isEnabled()) {
            signalStrength.setText(R.string.wifiDisabled);
            ssid.setText(R.string.wifiDisabled);
            bssid.setText(R.string.wifiDisabled);

            return;
        }

        if (!info.isConnected()) {
            signalStrength.setText(R.string.noWifiConnection);
            ssid.setText(R.string.noWifiConnection);
            bssid.setText(R.string.noWifiConnection);

            return;
        }

        signalHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                signalStrength.setText(String.format(getResources().getString(R.string.signalLink), wifi.getSignalStrength(), wifi.getLinkSpeed()));
                signalHandler.postDelayed(this, TIMER_INTERVAL);
            }
        }, 0);

        getInternalIp();
        getExternalIp();

        ssid.setText(wifi.getSSID());
        bssid.setText(wifi.getBSSID());
    }

    /**
     * Sets up event handlers and items for the left drawer
     */
    private void setupDrawer() {
        final DrawerLayout leftDrawer = (DrawerLayout) findViewById(R.id.leftDrawer);
        final RelativeLayout leftDrawerLayout = (RelativeLayout) findViewById(R.id.leftDrawerLayout);

        ImageView drawerIcon = (ImageView) findViewById(R.id.leftDrawerIcon);
        drawerIcon.setOnClickListener(new View.OnClickListener() {

            /**
             * Open the left drawer when the users taps on the icon
             * @param v
             */
            @Override
            public void onClick(View v) {
                leftDrawer.openDrawer(GravityCompat.START);
            }
        });

        ListView upperList = (ListView) findViewById(R.id.upperLeftDrawerList);
        ListView lowerList = (ListView) findViewById(R.id.lowerLeftDrawerList);

        upperList.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            /**
             * Click handler for the left side navigation drawer items
             * @param parent
             * @param view
             * @param position
             * @param id
             */
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        startActivity(new Intent(MainActivity.this, WanHostActivity.class));
                        break;
                    case 1:
                        startActivity(new Intent(MainActivity.this, DnsActivity.class));
                        break;
                }
                leftDrawer.closeDrawer(leftDrawerLayout);
            }
        });

        lowerList.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            /**
             * Click handler for the left side navigation drawer items
             * @param parent
             * @param view
             * @param position
             * @param id
             */
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        startActivity(new Intent(MainActivity.this, PreferencesActivity.class));
                        break;
                }
                leftDrawer.closeDrawer(leftDrawerLayout);
            }
        });
    }

    /**
     * Wrapper method for getting the internal wireless IP address.
     * This gets the netmask, counts the bits set (subnet size),
     * then prints it along side the IP.
     */
    private void getInternalIp() {
        int netmask = wifi.getInternalWifiSubnet();
        try {
            String internalIpWithSubnet = wifi.getInternalWifiIpAddress(String.class) + "/" + Integer.toString(netmask);
            internalIp.setText(internalIpWithSubnet);
        } catch (UnknownHostException e) {
            Errors.showError(getApplicationContext(), getResources().getString(R.string.notConnectedLan));
        }

    }

    /**
     * Wrapper for getting the external IP address
     * We can control whether or not to do this based on the user's preference
     * If the user doesn't want this then hide the appropriate views
     */
    private void getExternalIp() {
        TextView label = (TextView) findViewById(R.id.externalIpAddressLabel);
        TextView ip = (TextView) findViewById(R.id.externalIpAddress);

        if (UserPreference.getFetchExternalIp(this)) {
            label.setVisibility(View.VISIBLE);
            ip.setVisibility(View.VISIBLE);

            if (cachedWanIp == null) {
                wifi.getExternalIpAddress(this);
            }
        } else {
            label.setVisibility(View.GONE);
            ip.setVisibility(View.GONE);
        }
    }

    /**
     * Activity paused
     */
    @Override
    public void onPause() {
        super.onPause();

        if (scanProgressDialog != null && scanProgressDialog.isShowing()) {
            scanProgressDialog.dismiss();
        }
        scanProgressDialog = null;
    }

    /**
     * Activity destroyed
     */
    @Override
    public void onDestroy() {
        super.onDestroy();

        signalHandler.removeCallbacksAndMessages(null);

        if (receiver != null) {
            unregisterReceiver(receiver);
        }
    }

    /**
     * Activity restarted
     */
    @Override
    public void onRestart() {
        super.onRestart();

        registerReceiver(receiver, intentFilter);
    }

    /**
     * Save the state of an activity
     *
     * @param savedState Data to save
     */
    @Override
    public void onSaveInstanceState(Bundle savedState) {
        super.onSaveInstanceState(savedState);

        ListAdapter adapter = hostList.getAdapter();
        if (adapter != null) {
            ArrayList<Host> adapterData = new ArrayList<>();
            for (int i = 0; i < adapter.getCount(); i++) {
                Host item = (Host) adapter.getItem(i);
                adapterData.add(item);
            }
            savedState.putSerializable("hosts", adapterData);
            savedState.putString("wanIp", cachedWanIp);
        }
    }

    /**
     * Activity state restored
     *
     * @param savedState Saved data from the saved state
     */
    @Override
    @SuppressWarnings("unchecked")
    public void onRestoreInstanceState(Bundle savedState) {
        super.onRestoreInstanceState(savedState);

        cachedWanIp = savedState.getString("wanIp");
        externalIp.setText(cachedWanIp);
        hosts = (ArrayList<Host>) savedState.getSerializable("hosts");
        if (hosts != null) {
            setupHostsAdapter();
        }
    }

    /**
     * Delegate to update the host list and dismiss the progress dialog
     * Gets called when host discovery has finished
     *
     * @param output The list of hosts to bind to the list view
     */
    @Override
    public void processFinish(final Host output) {
        scanHandler.post(new Runnable() {

            @Override
            public void run() {
                
                populate.setVisibility(View.GONE);

                hosts.add(output);
                hostAdapter.sort(new Comparator<Host>() {

                    @Override
                    public int compare(Host lhs, Host rhs) {
                        try {
                            int leftIp = new BigInteger(InetAddress.getByName(lhs.getIp()).getAddress()).intValue();
                            int rightIp = new BigInteger(InetAddress.getByName(rhs.getIp()).getAddress()).intValue();

                            return leftIp - rightIp;
                        } catch (UnknownHostException ignored) {
                            return 0;
                        }
                    }
                });
                discoverHostsBtn.setText(discoverHostsStr + " (" + hosts.size() + ")");
            }
        });
    }

    /**
     * Delegate to update the progress of the host discovery scan
     *
     * @param output The amount of progress to increment by
     */
    @Override
    public void processFinish(int output) {
        if (scanProgressDialog != null && scanProgressDialog.isShowing()) {
            scanProgressDialog.incrementProgressBy(output);
        }
    }

    /**
     * Delegate to handle setting the external IP in the UI
     *
     * @param output External IP
     */
    @Override
    public void processFinish(String output) {
        cachedWanIp = output;
        externalIp.setText(output);
    }

    /**
     * Delegate to dismiss the progress dialog
     *
     * @param output
     */
    @Override
    public void processFinish(final boolean output) {
        scanHandler.post(new Runnable() {

            @Override
            public void run() {
                if (output && scanProgressDialog != null && scanProgressDialog.isShowing()) {
                    scanProgressDialog.dismiss();
                }
            }
        });
    }

    /**
     * Delegate to handle bubbled up errors
     *
     * @param output The exception we want to handle
     * @param <T>    Exception
     */
    @Override
    public <T extends Throwable> void processFinish(final T output) {
        scanHandler.post(new Runnable() {

            @Override
            public void run() {
                Errors.showError(getApplicationContext(), output.getLocalizedMessage());
            }
        });
    }
}
