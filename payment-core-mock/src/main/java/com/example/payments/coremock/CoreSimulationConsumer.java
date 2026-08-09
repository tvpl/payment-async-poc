package com.example.payments.coremock;

import com.example.payments.common.avro.ProcessPaymentSimulationCommand;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.Fees;
import com.example.payments.common.model.ProcessPaymentSimulationCommandPayload;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Simulated external Core. Consumes {@code ProcessPaymentSimulationCommand} (Avro),
 * fakes authorization + fee computation (with an occasional decline), and replies on
 * {@code payment.simulation.core.response}. Intentionally minimal.
 */
@KafkaListener(groupId = "payment-core-mock", offsetReset = OffsetReset.EARLIEST)
public class CoreSimulationConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(CoreSimulationConsumer.class);

    private static final BigDecimal MDR_PERCENT = new BigDecimal("2.49");
    private static final BigDecimal INTERCHANGE_PERCENT = new BigDecimal("1.25");

    private final AvroSerde avroSerde;
    private final CoreResponseProducer producer;
    private final CoreBehaviorProperties behavior;
    private final CoreSimulationDecisionEngine decisionEngine;

    public CoreSimulationConsumer(AvroSerde avroSerde, CoreResponseProducer producer,
                                  CoreBehaviorProperties behavior,
                                  CoreSimulationDecisionEngine decisionEngine) {
        this.avroSerde = avroSerde;
        this.producer = producer;
        this.behavior = behavior;
        this.decisionEngine = decisionEngine;
    }

    @Topic(Topics.CORE_COMMAND)
    public void onCommand(ConsumerRecord<String, byte[]> record) throws Exception {
        ProcessPaymentSimulationCommand avro = avroSerde.deserialize(record.topic(), record.value());
        EventEnvelope<ProcessPaymentSimulationCommandPayload> env = AvroMapper.fromAvro(avro);
        ProcessPaymentSimulationCommandPayload cmd = env.payload();

        CoreSimulationDecisionEngine.Decision decision =
                decisionEngine.decide(env.requestId(), behavior.snapshot());
        Thread.sleep(decision.latencyMs());

        if (decision.outcome() == CoreSimulationDecisionEngine.Outcome.TRANSIENT_FAILURE) {
            LOG.warn("Core simulating transient failure requestId={} simulationId={}",
                    env.requestId(), cmd.simulationId());
            throw new RuntimeException("Simulated transient Core failure");
        }

        LocalDate commandDate = env.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        CorePaymentSimulationResponsePayload response = process(cmd, decision, commandDate);

        EventEnvelope<CorePaymentSimulationResponsePayload> out =
                env.deriveAs(EventTypes.CORE_PAYMENT_SIMULATION_RESPONSE, Sources.CORE, response);

        byte[] bytes = avroSerde.serialize(Topics.CORE_RESPONSE, AvroMapper.toAvroCoreResponse(out));
        String traceparent = header(record, Headers.TRACEPARENT);
        producer.send(env.requestId(), env.requestId(), traceparent == null ? "" : traceparent, bytes);
        LOG.info("Core replied status={} requestId={} simulationId={}",
                response.status(), env.requestId(), cmd.simulationId());
    }

    private CorePaymentSimulationResponsePayload process(
            ProcessPaymentSimulationCommandPayload cmd,
            CoreSimulationDecisionEngine.Decision decision,
            LocalDate commandDate) {
        BigDecimal amount = cmd.request().amount();
        if (decision.outcome() == CoreSimulationDecisionEngine.Outcome.DECLINED) {
            return new CorePaymentSimulationResponsePayload(
                    cmd.simulationId(), SimulationResult.DECLINED, null,
                    amount, cmd.request().currency(), cmd.request().installments(),
                    null, null, "51", "Insufficient funds");
        }

        BigDecimal fee = amount.multiply(MDR_PERCENT)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal netAmount = amount.subtract(fee).setScale(2, RoundingMode.HALF_UP);
        Fees fees = new Fees(MDR_PERCENT, INTERCHANGE_PERCENT, netAmount);

        int installments = cmd.request().installments() == null ? 1 : cmd.request().installments();
        Settlement settlement = new Settlement(
                commandDate.plusDays(1),
                installments > 1 ? "D+" + installments : "D+1");

        return new CorePaymentSimulationResponsePayload(
                cmd.simulationId(), SimulationResult.APPROVED, decision.authorizationCode(),
                amount, cmd.request().currency(), installments,
                fees, settlement, null, null);
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
