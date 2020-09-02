/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.trade.txproof.xmr;

import bisq.core.trade.txproof.AssetTxProofRequest;

import bisq.network.Socks5ProxyProvider;

import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.handlers.FaultHandler;
import bisq.common.util.Utilities;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

/**
 * Requests for the XMR tx proof for a particular trade from a particular service.
 * Repeats every 90 sec requests if tx is not confirmed or found yet until MAX_REQUEST_PERIOD of 12 hours is reached.
 */
@Slf4j
@EqualsAndHashCode
class XmrTxProofRequest implements AssetTxProofRequest<XmrTxProofRequest.Result> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Enums
    ///////////////////////////////////////////////////////////////////////////////////////////

    enum Result implements AssetTxProofRequest.Result {
        PENDING,    // Tx not visible in network yet, unconfirmed or not enough confirmations
        SUCCESS,    // Proof succeeded
        FAILED,     // Proof failed
        ERROR;      // Error from service, does not mean that proof failed

        @Nullable
        @Getter
        private Detail detail;

        Result with(Detail detail) {
            this.detail = detail;
            return this;
        }

        @Override
        public String toString() {
            return "Result{" +
                    "\n     detail=" + detail +
                    "\n} " + super.toString();
        }
    }

    enum Detail {
        // Pending
        TX_NOT_FOUND, // Tx not visible in network yet. Could be also other error
        PENDING_CONFIRMATIONS,

        // Error states
        CONNECTION_FAILURE,
        API_FAILURE,
        API_INVALID,

        // Failure states
        TX_HASH_INVALID,
        TX_KEY_INVALID,
        ADDRESS_INVALID,
        NO_MATCH_FOUND,
        AMOUNT_NOT_MATCHING,
        TRADE_DATE_NOT_MATCHING,
        NO_RESULTS_TIMEOUT;

        @Getter
        private int numConfirmations;
        @Nullable
        @Getter
        private String errorMsg;

        public Detail error(String errorMsg) {
            this.errorMsg = errorMsg;
            return this;
        }

        public Detail numConfirmations(int numConfirmations) {
            this.numConfirmations = numConfirmations;
            return this;
        }

        @Override
        public String toString() {
            return "Detail{" +
                    "\n     numConfirmations=" + numConfirmations +
                    ",\n     errorMsg='" + errorMsg + '\'' +
                    "\n} " + super.toString();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Static fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static final long REPEAT_REQUEST_PERIOD = TimeUnit.SECONDS.toMillis(90);
    private static final long MAX_REQUEST_PERIOD = TimeUnit.HOURS.toMillis(12);

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final ListeningExecutorService executorService = Utilities.getListeningExecutorService(
            "XmrTransferProofRequester", 3, 5, 10 * 60);
    private final XmrTxProofHttpClient httpClient;
    private final XmrTxProofParser xmrTxProofParser;
    private final XmrTxProofModel xmrTxProofModel;
    private final long firstRequest;

    private boolean terminated;
    @Getter
    @Nullable
    private Result result;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    XmrTxProofRequest(Socks5ProxyProvider socks5ProxyProvider,
                      XmrTxProofParser xmrTxProofParser,
                      XmrTxProofModel xmrTxProofModel) {
        this.xmrTxProofParser = xmrTxProofParser;
        this.xmrTxProofModel = xmrTxProofModel;

        httpClient = new XmrTxProofHttpClient(socks5ProxyProvider);
        httpClient.setBaseUrl("http://" + xmrTxProofModel.getServiceAddress());
        if (xmrTxProofModel.getServiceAddress().matches("^192.*|^localhost.*")) {
            log.info("Ignoring Socks5 proxy for local net address: {}", xmrTxProofModel.getServiceAddress());
            httpClient.setIgnoreSocks5Proxy(true);
        }

        terminated = false;
        firstRequest = System.currentTimeMillis();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("SpellCheckingInspection")
    @Override
    public void requestFromService(Consumer<Result> resultHandler, FaultHandler faultHandler) {
        if (terminated) {
            // the XmrTransferProofService has asked us to terminate i.e. not make any further api calls
            // this scenario may happen if a re-request is scheduled from the callback below
            log.warn("Not starting {} as we have already terminated.", this);
            return;
        }

        // Timeout handing is delegated to the connection timeout handling in httpClient.

        ListenableFuture<Result> future = executorService.submit(() -> {
            Thread.currentThread().setName("XmrTransferProofRequest-" + this.getShortId());
            String param = "/api/outputs?txhash=" + xmrTxProofModel.getTxHash() +
                    "&address=" + xmrTxProofModel.getRecipientAddress() +
                    "&viewkey=" + xmrTxProofModel.getTxKey() +
                    "&txprove=1";
            log.info("Param {} for {}", param, this);
            String json = httpClient.requestWithGET(param, "User-Agent", "bisq/" + Version.VERSION);
            String prettyJson = new GsonBuilder().setPrettyPrinting().create().toJson(new JsonParser().parse(json));
            log.info("Response json from {}\n{}", this, prettyJson);
            Result result = xmrTxProofParser.parse(xmrTxProofModel, json);
            log.info("Result from {}\n{}", this, result);
            return result;
        });

        Futures.addCallback(future, new FutureCallback<>() {
            public void onSuccess(Result result) {
                XmrTxProofRequest.this.result = result;

                if (terminated) {
                    log.warn("We received {} but {} was terminated already. We do not process result.", result, this);
                    return;
                }

                switch (result) {
                    case PENDING:
                        if (isTimeOutReached()) {
                            log.warn("{} took too long without a success or failure/error result We give up. " +
                                    "Might be that the transaction was never published.", this);
                            // If we reached out timeout we return with an error.
                            UserThread.execute(() -> resultHandler.accept(XmrTxProofRequest.Result.ERROR.with(Detail.NO_RESULTS_TIMEOUT)));
                        } else {
                            UserThread.runAfter(() -> requestFromService(resultHandler, faultHandler), REPEAT_REQUEST_PERIOD, TimeUnit.MILLISECONDS);
                            // We update our listeners
                            UserThread.execute(() -> resultHandler.accept(result));
                        }
                        break;
                    case SUCCESS:
                        log.info("{} succeeded", result);
                        UserThread.execute(() -> resultHandler.accept(result));
                        terminate();
                        break;
                    case FAILED:
                    case ERROR:
                        UserThread.execute(() -> resultHandler.accept(result));
                        terminate();
                        break;
                    default:
                        log.warn("Unexpected result {}", result);
                        break;
                }
            }

            public void onFailure(@NotNull Throwable throwable) {
                String errorMessage = this + " failed with error " + throwable.toString();
                faultHandler.handleFault(errorMessage, throwable);
                UserThread.execute(() ->
                        resultHandler.accept(XmrTxProofRequest.Result.ERROR.with(Detail.CONNECTION_FAILURE.error(errorMessage))));
            }
        });
    }

    @Override
    public void terminate() {
        terminated = true;
    }

    // Convenient for logging
    @Override
    public String toString() {
        return "Request at: " + xmrTxProofModel.getServiceAddress() + " for trade: " + xmrTxProofModel.getTradeId();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private String getShortId() {
        return Utilities.getShortId(xmrTxProofModel.getTradeId()) + " @ " +
                xmrTxProofModel.getServiceAddress().substring(0, 6);
    }

    private boolean isTimeOutReached() {
        return System.currentTimeMillis() - firstRequest > MAX_REQUEST_PERIOD;
    }

}
