package org.mifos.connector.mojaloop.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.mojaloop.ilp.Ilp;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.connector.mojaloop.model.QuoteCallbackDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.QUOTE_CALLBACK;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.QUOTE_ERROR;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PAYEE_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_ID;

@Component
public class QuoteResponseProcessor implements Processor {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired(required = false)
    private ZeebeClient zeebeClient;

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${mojaloop.enabled}")
    private boolean isMojaloopEnabled;

    @Override
    public void process(Exchange exchange) throws JsonProcessingException {
        Map<String, Object> variables = new HashMap<>();

        String messageName;
        String error = exchange.getIn().getBody(String.class);
        if (exchange.getProperty(QUOTE_FAILED, false, Boolean.class)) {
            messageName = QUOTE_ERROR;
            variables.put(ERROR_INFORMATION, error);
            variables.put(QUOTE_FAILED, true);
        } else {
            QuoteCallbackDTO response = exchange.getIn().getBody(QuoteCallbackDTO.class);
            logger.debug("ILP PACKET: {}", response.getIlpPacket());
            logger.debug("CONDITION: {}", response.getCondition());
            messageName = QUOTE_CALLBACK;
            variables.put(PAYEE_QUOTE_RESPONSE, objectMapper.writeValueAsString(response));
            // DIFME patch: upstream re-derived the payee's condition with this connector's own ILP secret, which only
            // works when payer and payee share one secret (both FSPs as tenants of one hub). Per the Mojaloop spec the
            // payer cannot recompute it (it is the SHA-256 of the payee's secret fulfilment); it checks the packet is
            // for this quote and the condition is a well-formed SHA-256. The fulfilment is checked at commit.
            if (isMojaloopEnabled && !isWellFormedQuote(response.getIlpPacket(), response.getCondition(),
                    exchange.getIn().getHeader(QUOTE_ID, String.class))) {
                logger.error("Invalid ILP packet for quote: {}", exchange.getIn().getHeader(QUOTE_ID));
                variables.put(QUOTE_FAILED, true);
            } else {
                variables.put(QUOTE_FAILED, false);
            }
        }

        if(zeebeClient != null) {
            zeebeClient.newPublishMessageCommand()
                    .messageName(messageName)
                    .correlationKey(exchange.getIn().getHeader(QUOTE_ID, String.class))
                    .timeToLive(Duration.ofMillis(30000))
                    .variables(variables)
                    .send();
        } else {
            logger.error("Mojaloop quote request failed: {}", error);
        }
    }

    /** DIFME patch: the ILP packet decodes and carries this quote, and the condition is 32 bytes (base64url). */
    private boolean isWellFormedQuote(String packet, String condition, String quoteId) {
        try {
            byte[] c = java.util.Base64.getUrlDecoder().decode(condition);
            Ilp ilp = ilpBuilder.parse(packet, condition);
            return c.length == 32 && ilp.getTransaction() != null
                    && (quoteId == null || quoteId.equals(ilp.getTransaction().getQuoteId()));
        } catch (RuntimeException e) {
            logger.error("ILP packet for quote {} does not decode: {}", quoteId, e.getMessage());
            return false;
        }
    }
}
