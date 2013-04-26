/*
My main activity implements LocationListener, so it can be passed to the LocationManager to receive GPS events.
Here's what I do when creating my activity:
*/
public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    ((TextView) this.findViewById(R.id.textView)).setText("Something else");

    // LocationManager locationManager = (LocationManager)
    // getSystemService(Context.LOCATION_SERVICE);
    // locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
    // 0, 0, this);

    LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

    String mocLocationProvider = LocationManager.GPS_PROVIDER;
    locationManager.addTestProvider(mocLocationProvider, false, false,
            false, false, true, true, true, 0, 5);
    locationManager.setTestProviderEnabled(mocLocationProvider, true);
    locationManager.requestLocationUpdates(mocLocationProvider, 0, 0, this);

    try {

        List data = new ArrayList();
        InputStream is = getAssets().open("data.txt");
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line = null;
        while ((line = reader.readLine()) != null) {

            data.add(line);
        }
        Log.e(LOG_TAG, data.size() + " lines");

        new MockLocationProvider(locationManager, mocLocationProvider, data).start();

    } catch (IOException e) {

        e.printStackTrace();
    }
}

/*
This will basically setup the test location provider and read the points into a list.
Then I feed that stuff to my mock location provider (just a normal thread) that
will read them 1 per second and trigger the new location back to this activity.
Here's the code for MockLocationProvider:
*/
public class MockLocationProvider extends Thread {
    private List data;

    private LocationManager locationManager;

    private String mocLocationProvider;

    private String LOG_TAG = "faren";

    public MockLocationProvider(LocationManager locationManager,
            String mocLocationProvider, List data) throws IOException {

        this.locationManager = locationManager;
        this.mocLocationProvider = mocLocationProvider;
        this.data = data;
    }

    @Override
    public void run() {

        for (String str : data) {

            try {

                Thread.sleep(1000);

            } catch (InterruptedException e) {

                e.printStackTrace();
            }

            // Set one position
            String[] parts = str.split(",");
            Double latitude = Double.valueOf(parts[0]);
            Double longitude = Double.valueOf(parts[1]);
            Double altitude = Double.valueOf(parts[2]);
            Location location = new Location(mocLocationProvider);
            location.setLatitude(latitude);
            location.setLongitude(longitude);
            location.setAltitude(altitude);

            Log.e(LOG_TAG, location.toString());

            // set the time in the location. If the time on this location
            // matches the time on the one in the previous set call, it will be
            // ignored
            location.setTime(System.currentTimeMillis());

            locationManager.setTestProviderLocation(mocLocationProvider, location);
        }
    }
}

/*
Notice the location.setTime() call. Read the comment why it is necessary.
Took me forever to find this one in google :D
Peace and great Androiding ;)
*/
