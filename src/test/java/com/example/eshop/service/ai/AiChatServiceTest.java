package com.example.eshop.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiChatServiceTest {
    private final ProductChatRetrieval retrieval = mock();
    private final ObjectProvider<ChatModel> model = mock();
    private final AiChatService chat = new AiChatService(retrieval, model);

    @Test
    void databaseFailureReturnsServiceUnavailableWithoutInternalDetails() {
        String question = "Laptop dưới 15 triệu";
        when(retrieval.retrieve(eq(question), any())).thenThrow(new DataAccessResourceFailureException("private JDBC failure"));
        var failure = assertThrows(ResponseStatusException.class,
                () -> chat.chat(new MockHttpSession(), null, null, question));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        assertTrue(failure.getReason().contains("chưa đọc được dữ liệu"));
        assertFalse(failure.getReason().contains("JDBC"));
        verifyNoInteractions(model);
    }

    @Test
    void chatLimitsBurstRequestsAcrossSessions() {
        var session = new MockHttpSession();
        var id = chat.chat(session, null, null, "Xin chào").conversationId();
        for (int turn = 1; turn < 30; turn++) {
            assertEquals(id, chat.chat(session, null, id, "Xin chào").conversationId());
        }
        var limited = assertThrows(ResponseStatusException.class,
                () -> chat.chat(new MockHttpSession(), null, null, "Xin chào"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatusCode());
        verifyNoInteractions(retrieval, model);
    }
}
