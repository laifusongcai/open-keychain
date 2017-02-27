/*
 * Copyright (C) 2016 Vincent Breitmoser <look@my.amazin.horse>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.remote;

import java.security.AccessControlException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.provider.ApiDataAccessObject;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.KeychainContract.ApiApps;
import org.sufficientlysecure.keychain.provider.KeychainContract.Certs;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.KeychainContract.UserPackets;
import org.sufficientlysecure.keychain.provider.KeychainDatabase;
import org.sufficientlysecure.keychain.provider.KeychainDatabase.Tables;
import org.sufficientlysecure.keychain.provider.KeychainExternalContract;
import org.sufficientlysecure.keychain.provider.KeychainExternalContract.ApiTrustIdentity;
import org.sufficientlysecure.keychain.provider.KeychainExternalContract.EmailStatus;
import org.sufficientlysecure.keychain.provider.SimpleContentResolverInterface;
import org.sufficientlysecure.keychain.util.Log;

public class KeychainExternalProvider extends ContentProvider implements SimpleContentResolverInterface {
    private static final int EMAIL_STATUS = 101;
    private static final int TRUST_IDENTITY = 201;
    private static final int API_APPS = 301;
    private static final int API_APPS_BY_PACKAGE_NAME = 302;

    public static final String TEMP_TABLE_QUERIED_ADDRESSES = "queried_addresses";
    public static final String TEMP_TABLE_COLUMN_ADDRES = "address";


    private UriMatcher mUriMatcher;
    private ApiPermissionHelper mApiPermissionHelper;


    /**
     * Build and return a {@link UriMatcher} that catches all {@link Uri} variations supported by
     * this {@link ContentProvider}.
     */
    protected UriMatcher buildUriMatcher() {
        final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);

        String authority = KeychainExternalContract.CONTENT_AUTHORITY_EXTERNAL;

        /**
         * list email_status
         *
         * <pre>
         * email_status/
         * </pre>
         */
        matcher.addURI(authority, KeychainExternalContract.BASE_EMAIL_STATUS, EMAIL_STATUS);

        matcher.addURI(authority, KeychainExternalContract.BASE_TRUST_IDENTITIES + "/*", TRUST_IDENTITY);

        // can only query status of calling app - for internal use only!
        matcher.addURI(KeychainContract.CONTENT_AUTHORITY, KeychainContract.BASE_API_APPS + "/*", API_APPS_BY_PACKAGE_NAME);

        return matcher;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onCreate() {
        mUriMatcher = buildUriMatcher();
        mApiPermissionHelper = new ApiPermissionHelper(getContext(), new ApiDataAccessObject(this));
        return true;
    }

    public KeychainDatabase getDb() {
        return new KeychainDatabase(getContext());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getType(@NonNull Uri uri) {
        final int match = mUriMatcher.match(uri);
        switch (match) {
            case EMAIL_STATUS:
                return EmailStatus.CONTENT_TYPE;

            case API_APPS:
                return ApiApps.CONTENT_TYPE;

            case API_APPS_BY_PACKAGE_NAME:
                return ApiApps.CONTENT_ITEM_TYPE;

            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        Log.v(Constants.TAG, "query(uri=" + uri + ", proj=" + Arrays.toString(projection) + ")");

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        int match = mUriMatcher.match(uri);

        String groupBy = null;

        SQLiteDatabase db = getDb().getReadableDatabase();

        switch (match) {
            case EMAIL_STATUS: {
                boolean callerIsAllowed = mApiPermissionHelper.isAllowedIgnoreErrors();
                if (!callerIsAllowed) {
                    throw new AccessControlException("An application must register before use of KeychainExternalProvider!");
                }

                db.execSQL("CREATE TEMPORARY TABLE " + TEMP_TABLE_QUERIED_ADDRESSES + " (" + TEMP_TABLE_COLUMN_ADDRES + " TEXT);");
                ContentValues cv = new ContentValues();
                for (String address : selectionArgs) {
                    cv.put(TEMP_TABLE_COLUMN_ADDRES, address);
                    db.insert(TEMP_TABLE_QUERIED_ADDRESSES, null, cv);
                }

                HashMap<String, String> projectionMap = new HashMap<>();
                projectionMap.put(EmailStatus._ID, "email AS _id");
                projectionMap.put(EmailStatus.EMAIL_ADDRESS, // this is actually the queried address
                        TEMP_TABLE_QUERIED_ADDRESSES + "." + TEMP_TABLE_COLUMN_ADDRES + " AS " + EmailStatus.EMAIL_ADDRESS);
                // we take the minimum (>0) here, where "1" is "verified by known secret key", "2" is "self-certified"
                projectionMap.put(EmailStatus.EMAIL_STATUS, "CASE ( MIN (" + Certs.VERIFIED + " ) ) "
                        // remap to keep this provider contract independent from our internal representation
                        + " WHEN " + Certs.VERIFIED_SELF + " THEN 1"
                        + " WHEN " + Certs.VERIFIED_SECRET + " THEN 2"
                        + " WHEN NULL THEN NULL"
                        + " END AS " + EmailStatus.EMAIL_STATUS);
                projectionMap.put(EmailStatus.USER_ID,
                        Tables.USER_PACKETS + "." + UserPackets.USER_ID + " AS " + EmailStatus.USER_ID);
                projectionMap.put(EmailStatus.TRUST_ID_LAST_UPDATE, Tables.API_TRUST_IDENTITIES + "." +
                        ApiTrustIdentity.LAST_UPDATED + " AS " + EmailStatus.TRUST_ID_LAST_UPDATE);
                qb.setProjectionMap(projectionMap);

                if (projection == null) {
                    throw new IllegalArgumentException("Please provide a projection!");
                }

                String callingPackageName = mApiPermissionHelper.getCurrentCallingPackage();

                qb.setTables(
                        TEMP_TABLE_QUERIED_ADDRESSES
                                + " LEFT JOIN " + Tables.USER_PACKETS + " ON ("
                                    + Tables.USER_PACKETS + "." + UserPackets.USER_ID + " IS NOT NULL"
                                    + " AND " + Tables.USER_PACKETS + "." + UserPackets.EMAIL + " LIKE " + TEMP_TABLE_QUERIED_ADDRESSES + "." + TEMP_TABLE_COLUMN_ADDRES
                                + ")"
                                + " LEFT JOIN " + Tables.API_TRUST_IDENTITIES + " ON ("
                                    + Tables.API_TRUST_IDENTITIES + "." + ApiTrustIdentity.PACKAGE_NAME + " = \"" + callingPackageName + "\""
                                    + " AND " + Tables.API_TRUST_IDENTITIES + "." + ApiTrustIdentity.IDENTIFIER + " LIKE queried_addresses.address"
                                + ")"
                                + " JOIN " + Tables.CERTS + " ON ("
                                    + "(" + Tables.USER_PACKETS + "." + UserPackets.MASTER_KEY_ID + " = " + Tables.CERTS + "." + Certs.MASTER_KEY_ID
                                    + " AND " + Tables.USER_PACKETS + "." + UserPackets.RANK + " = " + Tables.CERTS + "." + Certs.RANK + ")"
                                    + " OR " + Tables.API_TRUST_IDENTITIES + "." + ApiTrustIdentity.MASTER_KEY_ID + " = " + Tables.CERTS + "." + Certs.MASTER_KEY_ID
                                + ")"
                );
                // in case there are multiple verifying certificates
                groupBy = TEMP_TABLE_QUERIED_ADDRESSES + "." + TEMP_TABLE_COLUMN_ADDRES
                        + ", " + Tables.CERTS + "." + UserPackets.MASTER_KEY_ID;
                List<String> plist = Arrays.asList(projection);
                if (plist.contains(EmailStatus.USER_ID)) {
                    groupBy += ", " + Tables.USER_PACKETS + "." + UserPackets.USER_ID;
                }

                // verified == 0 has no self-cert, which is basically an error case. never return that!
                // verified == null is fine, because it means there was no join partner
                qb.appendWhere(Tables.CERTS + "." + Certs.VERIFIED + " IS NULL OR " + Tables.CERTS + "." + Certs.VERIFIED + " > 0");

                if (TextUtils.isEmpty(sortOrder)) {
                    sortOrder = EmailStatus.EMAIL_ADDRESS;
                }

                // uri to watch is all /key_rings/
                uri = KeyRings.CONTENT_URI;
                break;
            }

            case TRUST_IDENTITY: {
                boolean callerIsAllowed = mApiPermissionHelper.isAllowedIgnoreErrors();
                if (!callerIsAllowed) {
                    throw new AccessControlException("An application must register before use of KeychainExternalProvider!");
                }

                if (projection == null) {
                    throw new IllegalArgumentException("Please provide a projection!");
                }

                HashMap<String, String> projectionMap = new HashMap<>();
                projectionMap.put(ApiTrustIdentity._ID, "oid AS " + ApiTrustIdentity._ID);
                projectionMap.put(ApiTrustIdentity.IDENTIFIER, ApiTrustIdentity.IDENTIFIER);
                projectionMap.put(ApiTrustIdentity.MASTER_KEY_ID, ApiTrustIdentity.MASTER_KEY_ID);
                projectionMap.put(ApiTrustIdentity.LAST_UPDATED, ApiTrustIdentity.LAST_UPDATED);
                qb.setProjectionMap(projectionMap);

                qb.setTables(Tables.API_TRUST_IDENTITIES);

                // allow access to columns of the calling package exclusively!
                qb.appendWhere(Tables.API_TRUST_IDENTITIES + "." + ApiTrustIdentity.PACKAGE_NAME +
                        " = " + mApiPermissionHelper.getCurrentCallingPackage());

                qb.appendWhere(Tables.API_TRUST_IDENTITIES + "." + ApiTrustIdentity.IDENTIFIER + " = ");
                qb.appendWhereEscapeString(uri.getLastPathSegment());

                break;
            }

            case API_APPS_BY_PACKAGE_NAME: {
                String requestedPackageName = uri.getLastPathSegment();
                checkIfPackageBelongsToCaller(getContext(), requestedPackageName);

                qb.setTables(Tables.API_APPS);
                qb.appendWhere(ApiApps.PACKAGE_NAME + " = ");
                qb.appendWhereEscapeString(requestedPackageName);

                break;
            }

            default: {
                throw new IllegalArgumentException("Unknown URI " + uri + " (" + match + ")");
            }

        }

        // If no sort order is specified use the default
        String orderBy;
        if (TextUtils.isEmpty(sortOrder)) {
            orderBy = null;
        } else {
            orderBy = sortOrder;
        }

        Cursor cursor = qb.query(db, projection, selection, null, groupBy, null, orderBy);
        if (cursor != null) {
            // Tell the cursor what uri to watch, so it knows when its source data changes
            cursor.setNotificationUri(getContext().getContentResolver(), uri);
        }

        Log.d(Constants.TAG,
                "Query: " + qb.buildQuery(projection, selection, groupBy, null, orderBy, null));

        return cursor;
    }

    private void checkIfPackageBelongsToCaller(Context context, String requestedPackageName) {
        int callerUid = Binder.getCallingUid();
        String[] callerPackageNames = context.getPackageManager().getPackagesForUid(callerUid);
        if (callerPackageNames == null) {
            throw new IllegalStateException("Failed to retrieve caller package name, this is an error!");
        }

        boolean packageBelongsToCaller = false;
        for (String p : callerPackageNames) {
            if (p.equals(requestedPackageName)) {
                packageBelongsToCaller = true;
                break;
            }
        }
        if (!packageBelongsToCaller) {
            throw new SecurityException("ExternalProvider may only check status of caller package!");
        }
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        Log.v(Constants.TAG, "insert(uri=" + uri + ")");

        int match = mUriMatcher.match(uri);
        if (match != TRUST_IDENTITY) {
            throw new UnsupportedOperationException();
        }

        boolean callerIsAllowed = mApiPermissionHelper.isAllowedIgnoreErrors();
        if (!callerIsAllowed) {
            throw new AccessControlException("An application must register before use of KeychainExternalProvider!");
        }

        Long masterKeyId = values.getAsLong(ApiTrustIdentity.MASTER_KEY_ID);
        if (masterKeyId == null) {
            throw new IllegalArgumentException("master_key_id must be a non-null value!");
        }

        ContentValues actualValues = new ContentValues();
        actualValues.put(ApiTrustIdentity.PACKAGE_NAME, mApiPermissionHelper.getCurrentCallingPackage());
        actualValues.put(ApiTrustIdentity.IDENTIFIER, uri.getLastPathSegment());
        actualValues.put(ApiTrustIdentity.MASTER_KEY_ID, masterKeyId);
        actualValues.put(ApiTrustIdentity.LAST_UPDATED, new Date().getTime() / 1000);

        SQLiteDatabase db = getDb().getWritableDatabase();
        try {
            db.insert(Tables.API_TRUST_IDENTITIES, null, actualValues);
            return uri;
        } finally {
            db.close();
        }
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        Log.v(Constants.TAG, "delete(uri=" + uri + ")");

        int match = mUriMatcher.match(uri);
        if (match != TRUST_IDENTITY || selection != null || selectionArgs != null) {
            throw new UnsupportedOperationException();
        }

        boolean callerIsAllowed = mApiPermissionHelper.isAllowedIgnoreErrors();
        if (!callerIsAllowed) {
            throw new AccessControlException("An application must register before use of KeychainExternalProvider!");
        }

        String actualSelection = ApiTrustIdentity.PACKAGE_NAME + " = ? AND " + ApiTrustIdentity.IDENTIFIER + " = ?";
        String[] actualSelectionArgs = new String[] {
                mApiPermissionHelper.getCurrentCallingPackage(),
                uri.getLastPathSegment()
        };

        SQLiteDatabase db = getDb().getWritableDatabase();
        try {
            return db.delete(Tables.API_TRUST_IDENTITIES, actualSelection, actualSelectionArgs);
        } finally {
            db.close();
        }
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

}
