package com.gamenews.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface SteamGateway {
    List<JsonNode> fetch(long appId);
}
