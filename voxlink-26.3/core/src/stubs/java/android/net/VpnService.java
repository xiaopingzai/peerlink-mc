/*
 * Android stub for PC compilation only.
 * Not packaged into jar. Real class provided by Android runtime.
 */
package android.net;

import android.app.Service;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.ParcelFileDescriptor;

public class VpnService extends Service {

    public static class Builder {
        public Builder setSession(String session) { return this; }

        public Builder addDisallowedApplication(String packageName) throws NameNotFoundException {
            return this;
        }

        public ParcelFileDescriptor establish() { return null; }
    }
}
