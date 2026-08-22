package org.pk.practices.servicesmarketplace.quote;

import java.util.List;

public interface MessageRepository {
    void insert(Message message);
    List<Message> findByRequest(String requestId);
}
