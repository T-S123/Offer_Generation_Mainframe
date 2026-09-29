package com.lending.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import static com.lending.ai.Json.*;

/** Official OpenAI Java Responses client with strict output, no implicit retries and no model substitution. */
public final class AstraClient implements ModelClient,AutoCloseable {
    public static final int MAX_OUTPUT_TOKENS=16384;
    public static final String MODEL="gpt-6-astra";
    private final OpenAIClient client;
    public AstraClient(String key){
        client=key==null||key.isBlank()?null:OpenAIOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(90)).maxRetries(0).build();
    }
    public Reply complete(String instructions,JsonNode input,JsonNode schema){
        if(client==null)throw new Fault(503,"MODEL_NOT_CONFIGURED","Set OPENAI_API_KEY in the local runtime environment; no model request was sent");
        var builder=ResponseFormatTextJsonSchemaConfig.Schema.builder();
        schema.fields().forEachRemaining(e->builder.putAdditionalProperty(e.getKey(),JsonValue.from(MAPPER.convertValue(e.getValue(),Object.class))));
        var params=ResponseCreateParams.builder().model(MODEL).serviceTier(ResponseCreateParams.ServiceTier.DEFAULT).store(false).instructions(instructions).input(write(input)).maxOutputTokens(MAX_OUTPUT_TOKENS)
            .text(ResponseTextConfig.builder().format(ResponseFormatTextJsonSchemaConfig.builder().name("agent_decision").strict(true).schema(builder.build()).build()).build()).build();
        try{
            var response=client.responses().create(params);
            if(response.status().isEmpty()||!response.status().get().equals(ResponseStatus.COMPLETED))throw new Fault(503,"MODEL_INCOMPLETE","Model did not complete; no successful decision exists");
            var texts=response.output().stream().flatMap(i->i.message().stream()).flatMap(m->m.content().stream()).flatMap(c->c.outputText().stream()).map(ResponseOutputText::text).toList();
            if(texts.size()!=1)throw new Fault(503,"MODEL_REFUSAL_OR_OUTPUT","Model returned no single structured decision");
            return new Reply(read(texts.get(0)),response.model().toString(),response.usage().map(u->u.inputTokens()).orElse(0L),response.usage().map(u->u.outputTokens()).orElse(0L));
        }catch(Fault e){throw e;}catch(Exception e){throw new Fault(503,"MODEL_UNAVAILABLE","OpenAI request failed; inspect provider availability and account configuration");}
    }
    public void close(){if(client!=null)client.close();}
}


