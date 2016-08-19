package info.blockchain.wallet.datamanagers;

import android.support.annotation.VisibleForTesting;

import info.blockchain.api.Access;
import info.blockchain.wallet.access.AccessState;
import info.blockchain.wallet.payload.Payload;
import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.rxjava.RxUtil;
import info.blockchain.wallet.util.AESUtilWrapper;
import info.blockchain.wallet.util.AppUtil;
import info.blockchain.wallet.util.CharSequenceX;
import info.blockchain.wallet.util.PrefsUtil;
import info.blockchain.wallet.util.StringUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import piuk.blockchain.android.R;
import piuk.blockchain.android.di.Injector;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.exceptions.Exceptions;
import rx.schedulers.Schedulers;

public class AuthDataManager {

    @Inject protected PayloadManager mPayloadManager;
    @Inject protected PrefsUtil mPrefsUtil;
    @Inject protected Access mAccess;
    @Inject protected AppUtil mAppUtil;
    @Inject protected AESUtilWrapper mAESUtil;
    @Inject protected AccessState mAccessState;
    @Inject protected StringUtils mStringUtils;
    @VisibleForTesting protected int timer;

    public AuthDataManager() {
        Injector.getInstance().getAppComponent().inject(this);
    }

    public Observable<String> getEncryptedPayload(String guid, String sessionId) {
        return Observable.fromCallable(() -> mAccess.getEncryptedPayload(guid, sessionId))
                .compose(RxUtil.applySchedulers());
    }

    public Observable<String> getSessionId(String guid) {
        return Observable.fromCallable(() -> mAccess.getSessionId(guid))
                .compose(RxUtil.applySchedulers());
    }

    public Observable<Void> updatePayload(String sharedKey, String guid, CharSequenceX password) {
        return getUpdatePayloadObservable(sharedKey, guid, password)
                .compose(RxUtil.applySchedulers());
    }

    public Observable<CharSequenceX> validatePin(String pin) {
        return Observable.fromCallable(() -> mAccessState.validatePIN(pin))
                .compose(RxUtil.applySchedulers());
    }

    public Observable<Boolean> createPin(CharSequenceX password, String pin) {
        return Observable.fromCallable(() -> mAccessState.createPIN(password, pin))
                .compose(RxUtil.applySchedulers());
    }

    public Observable<Payload> createHdWallet(String password, String walletName) {
        return Observable.fromCallable(() -> mPayloadManager.createHDWallet(password, walletName))
                .compose(RxUtil.applySchedulers())
                .doOnNext(payload -> mAppUtil.setNewlyCreated(true));
    }

    public Observable<Payload> restoreHdWallet(String password, String passphrase) {
        return Observable.fromCallable(() -> mPayloadManager.restoreHDWallet(
                password, passphrase, mStringUtils.getString(R.string.default_wallet_name)))
                .doOnNext(payload -> {
                    if (payload == null) throw Exceptions.propagate(new Throwable("Save failed"));
                })
                .compose(RxUtil.applySchedulers());
    }

    public Observable<String> startPollingAuthStatus(String guid) {
        // Get session id
        return getSessionId(guid)
                // return Observable that emits ticks every two seconds, pass in Session ID
                .flatMap(sessionId -> Observable.interval(2, TimeUnit.SECONDS)
                        // For each emission from the timer, try to get the payload
                        .map(tick -> getEncryptedPayload(guid, sessionId).toBlocking().first())
                        // If auth not required, emit payload
                        .filter(s -> !s.equals(Access.KEY_AUTH_REQUIRED))
                        // If error called, emit Auth Required
                        .onErrorReturn(throwable -> Observable.just(Access.KEY_AUTH_REQUIRED).toBlocking().first())
                        // Make sure threading is correct
                        .compose(RxUtil.applySchedulers())
                        // Only emit the first object
                        .first());
    }

    private Observable<Void> getUpdatePayloadObservable(String sharedKey, String guid, CharSequenceX password) {
        return Observable.defer(() -> Observable.create(subscriber -> {
            try {
                mPayloadManager.initiatePayload(
                        sharedKey,
                        guid,
                        password,
                        new PayloadManager.InitiatePayloadListener() {
                            @Override
                            public void onInitSuccess() {
                                mPayloadManager.setTempPassword(password);
                                subscriber.onNext(null);
                                subscriber.onCompleted();
                            }

                            @Override
                            public void onInitPairFail() {
                                subscriber.onError(new PairFailThrowable());
                            }

                            @Override
                            public void onInitCreateFail(String s) {
                                subscriber.onError(new CreateFailThrowable());
                            }
                        });
            } catch (Exception e) {
                subscriber.onError(new Throwable(e));
            }
        }));
    }

    public Observable<Integer> createCheckEmailTimer() {
        timer = 2 * 60;

        return Observable.interval(0, 1, TimeUnit.SECONDS, Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(aLong -> timer--)
                .takeUntil(aLong -> timer < 0);
    }

    public void attemptDecryptPayload(CharSequenceX password, String guid, String payload, DecryptPayloadListener listener) {
        try {
            JSONObject jsonObject = new JSONObject(payload);

            if (jsonObject.has("payload")) {
                String encrypted_payload = jsonObject.getString("payload");

                int iterations = PayloadManager.WalletPbkdf2Iterations;
                if (jsonObject.has("pbkdf2_iterations")) {
                    iterations = jsonObject.getInt("pbkdf2_iterations");
                }

                String decrypted_payload = null;
                try {
                    decrypted_payload = mAESUtil.decrypt(encrypted_payload, password, iterations);
                } catch (Exception e) {
                    listener.onFatalError();
                }

                if (decrypted_payload != null) {
                    JSONObject decryptedJsonObject = new JSONObject(decrypted_payload);

                    if (decryptedJsonObject.has("sharedKey")) {
                        mPrefsUtil.setValue(PrefsUtil.KEY_GUID, guid);
                        mPayloadManager.setTempPassword(password);

                        String sharedKey = decryptedJsonObject.getString("sharedKey");
                        mAppUtil.setSharedKey(sharedKey);

                        updatePayload(sharedKey, guid, password)
                                .compose(RxUtil.applySchedulers())
                                .subscribe(new Subscriber<Void>() {
                                    @Override
                                    public void onCompleted() {
                                        mPrefsUtil.setValue(PrefsUtil.KEY_EMAIL_VERIFIED, true);
                                        listener.onSuccess();
                                    }

                                    @Override
                                    public void onError(Throwable e) {
                                        if (e instanceof CreateFailThrowable) {
                                            listener.onCreateFail();
                                        } else if (e instanceof PairFailThrowable) {
                                            listener.onPairFail();
                                        } else {
                                            listener.onFatalError();
                                        }
                                    }

                                    @Override
                                    public void onNext(Void aVoid) {
                                        // No-op
                                    }
                                });
                    }
                } else {
                    // Decryption failed
                    listener.onAuthFail();
                }
            }
        } catch (JSONException e) {
            listener.onFatalError();
        }
    }

    class PairFailThrowable extends Throwable {
        PairFailThrowable() {
            super();
        }
    }

    class CreateFailThrowable extends Throwable {
        CreateFailThrowable() {
            super();
        }
    }

    public interface DecryptPayloadListener {

        void onSuccess();

        void onPairFail();

        void onCreateFail();

        void onAuthFail();

        void onFatalError();
    }
}
