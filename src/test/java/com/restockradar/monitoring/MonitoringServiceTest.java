package com.restockradar.monitoring;

import com.restockradar.config.MonitoringProperties;
import com.restockradar.config.TelegramProperties;
import com.restockradar.target.Target;
import com.restockradar.target.TargetRepository;
import com.restockradar.telegram.TelegramClient;
import com.restockradar.telegram.TelegramUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonitoringServiceTest {

    private TargetRepository targetRepository;
    private FakeTelegramClient telegramClient;
    private TelegramProperties telegramProperties;
    private MonitoringProperties monitoringProperties;
    private FakeStoreMonitor storeMonitor;
    private MonitoringService monitoringService;

    private static final long OWNER_CHAT_ID = 987654321L;

    @BeforeEach
    void setUp() {
        targetRepository = mock(TargetRepository.class);
        when(targetRepository.save(any(Target.class))).thenAnswer(invocation -> invocation.getArgument(0));

        telegramClient = new FakeTelegramClient();

        telegramProperties = new TelegramProperties();
        telegramProperties.setBotToken("dummy-token");
        telegramProperties.setOwnerChatId(OWNER_CHAT_ID);

        monitoringProperties = new MonitoringProperties();
        monitoringProperties.setDefaultIntervalSeconds(600);
        monitoringProperties.setMinIntervalSeconds(300);
        monitoringProperties.setRequestSpacingMs(0);
        monitoringProperties.setOutOfStockConfirmations(2);
        monitoringProperties.setAlertOnFirstCheck(true);

        storeMonitor = new FakeStoreMonitor("GAMES_THE_SHOP");
        monitoringService = new MonitoringService(
                targetRepository,
                telegramClient,
                telegramProperties,
                monitoringProperties,
                List.of(storeMonitor)
        );
    }

    private Target createTarget(String status, Long alertMessageId, int outCount) {
        Target t = new Target("GAMES_THE_SHOP", "https://www.gamestheshop.com/product/1", "Test Product", 600);
        t.setId(1L);
        t.setStatus(status);
        t.setAlertMessageId(alertMessageId);
        t.setOutCount(outCount);
        return t;
    }

    @Test
    @DisplayName("UNKNOWN / BLOCKED result does not change state or trigger alert for null previous status")
    void unknownOrBlockedDoesNotChangeState_WhenNull() {
        Target t = createTarget(null, null, 0);
        Snapshot snapshot = Snapshot.unknown("HTTP 500 error");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isNull();
        assertThat(t.getAlertMessageId()).isNull();
        assertThat(t.getOutCount()).isEqualTo(0);
        assertThat(t.getLastEvidence()).isEqualTo("HTTP 500 error");
        assertThat(telegramClient.sentMessages).isEmpty();
    }

    @Test
    @DisplayName("UNKNOWN / BLOCKED result does not change state or delete alert when previous status is IN_STOCK")
    void unknownOrBlockedDoesNotChangeState_WhenInStock() {
        Target t = createTarget("IN_STOCK", 42L, 0);
        Snapshot snapshot = Snapshot.blocked("HTTP 403 Forbidden");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("IN_STOCK");
        assertThat(t.getAlertMessageId()).isEqualTo(42L);
        assertThat(t.getOutCount()).isEqualTo(0);
        assertThat(t.getLastEvidence()).isEqualTo("HTTP 403 Forbidden");
        assertThat(telegramClient.deletedMessageIds).isEmpty();
        assertThat(telegramClient.editedMessages).isEmpty();
    }

    @Test
    @DisplayName("Target BLOCKED for 5 consecutive checks triggers warning to owner once")
    void blockedConsecutivelyAlertsOwnerAtFive() {
        Target t = createTarget("IN_STOCK", 42L, 0);
        Snapshot blockedSnapshot = Snapshot.blocked("Access Denied");

        for (int i = 1; i <= 4; i++) {
            monitoringService.applyTransition(t, blockedSnapshot);
            assertThat(telegramClient.sentMessages).isEmpty();
        }

        // 5th check should trigger a warning
        monitoringService.applyTransition(t, blockedSnapshot);
        assertThat(telegramClient.sentMessages).hasSize(1);
        assertThat(telegramClient.sentMessages.get(0).text()).contains("BLOCKED for 5 consecutive checks");

        // 6th check should not send another warning
        monitoringService.applyTransition(t, blockedSnapshot);
        assertThat(telegramClient.sentMessages).hasSize(1);
    }

    @Test
    @DisplayName("IN_STOCK result on first check (previous null) sends alert when alertOnFirstCheck=true")
    void inStockOnFirstCheckSendsAlertWhenEnabled() {
        Target t = createTarget(null, null, 0);
        Snapshot snapshot = Snapshot.inStock(new BigDecimal("1000.00"), "Found in stock");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("IN_STOCK");
        assertThat(t.getAlertMessageId()).isNotNull();
        assertThat(t.getOutCount()).isEqualTo(0);
        assertThat(telegramClient.sentMessages).hasSize(1);
        assertThat(telegramClient.sentMessages.get(0).text())
                .contains("🟢 IN STOCK")
                .contains("Test Product")
                .contains("Price: ₹1000");
    }

    @Test
    @DisplayName("IN_STOCK result on first check does NOT send alert when alertOnFirstCheck=false")
    void inStockOnFirstCheckDoesNotSendAlertWhenDisabled() {
        monitoringProperties.setAlertOnFirstCheck(false);
        Target t = createTarget(null, null, 0);
        Snapshot snapshot = Snapshot.inStock(new BigDecimal("1000.00"), "Found in stock");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("IN_STOCK");
        assertThat(t.getAlertMessageId()).isNull();
        assertThat(telegramClient.sentMessages).isEmpty();
    }

    @Test
    @DisplayName("IN_STOCK result when previous is OUT_OF_STOCK sends alert, updates status and saves alertMessageId")
    void inStockWhenOutOfStockSendsAlert() {
        Target t = createTarget("OUT_OF_STOCK", null, 2);
        Snapshot snapshot = Snapshot.inStock(new BigDecimal("1500.00"), "Restocked");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("IN_STOCK");
        assertThat(t.getAlertMessageId()).isNotNull();
        assertThat(t.getOutCount()).isEqualTo(0);
        assertThat(telegramClient.sentMessages).hasSize(1);
        assertThat(telegramClient.sentMessages.get(0).text()).contains("🟢 IN STOCK");
    }

    @Test
    @DisplayName("IN_STOCK result when previous is already IN_STOCK produces no duplicate alerts")
    void inStockWhenAlreadyInStockDoesNothing() {
        Target t = createTarget("IN_STOCK", 42L, 0);
        Snapshot snapshot = Snapshot.inStock(new BigDecimal("1000.00"), "Still in stock");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("IN_STOCK");
        assertThat(t.getAlertMessageId()).isEqualTo(42L);
        assertThat(t.getOutCount()).isEqualTo(0);
        assertThat(telegramClient.sentMessages).isEmpty();
    }

    @Test
    @DisplayName("OUT_OF_STOCK on first check (previous null) sets baseline OUT_OF_STOCK with no alert")
    void outOfStockOnFirstCheckSetsBaselineWithoutAlert() {
        Target t = createTarget(null, null, 0);
        Snapshot snapshot = Snapshot.outOfStock(null, "Not in stock");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("OUT_OF_STOCK");
        assertThat(t.getAlertMessageId()).isNull();
        assertThat(t.getOutCount()).isEqualTo(0);
        assertThat(telegramClient.sentMessages).isEmpty();
        assertThat(telegramClient.deletedMessageIds).isEmpty();
    }

    @Test
    @DisplayName("OUT_OF_STOCK when previous is OUT_OF_STOCK does nothing")
    void outOfStockWhenOutOfStockDoesNothing() {
        Target t = createTarget("OUT_OF_STOCK", null, 0);
        Snapshot snapshot = Snapshot.outOfStock(null, "Still out of stock");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("OUT_OF_STOCK");
        assertThat(t.getAlertMessageId()).isNull();
        assertThat(telegramClient.sentMessages).isEmpty();
        assertThat(telegramClient.deletedMessageIds).isEmpty();
    }

    @Test
    @DisplayName("OUT_OF_STOCK check 1 when previous is IN_STOCK increments outCount to 1 without deleting alert")
    void outOfStockFirstConfirmationDoesNotDeleteAlert() {
        Target t = createTarget("IN_STOCK", 42L, 0);
        Snapshot snapshot = Snapshot.outOfStock(null, "First out check");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("IN_STOCK"); // status remains IN_STOCK until confirmations met
        assertThat(t.getOutCount()).isEqualTo(1);
        assertThat(t.getAlertMessageId()).isEqualTo(42L);
        assertThat(telegramClient.deletedMessageIds).isEmpty();
    }

    @Test
    @DisplayName("OUT_OF_STOCK check 2 deletes alert message and transitions to OUT_OF_STOCK")
    void outOfStockSecondConfirmationDeletesAlert() {
        Target t = createTarget("IN_STOCK", 42L, 1); // outCount was 1
        Snapshot snapshot = Snapshot.outOfStock(null, "Second out check");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("OUT_OF_STOCK");
        assertThat(t.getOutCount()).isEqualTo(0);
        assertThat(t.getAlertMessageId()).isNull();
        assertThat(telegramClient.deletedMessageIds).containsExactly(42L);
        assertThat(telegramClient.editedMessages).isEmpty();
    }

    @Test
    @DisplayName("OUT_OF_STOCK check 2 edits message to '🔴 Sold out' when delete fails")
    void outOfStockSecondConfirmationEditsWhenDeleteFails() {
        telegramClient.deleteSucceeds = false; // simulate Telegram delete failure (e.g. >48 hours old)
        Target t = createTarget("IN_STOCK", 42L, 1);
        Snapshot snapshot = Snapshot.outOfStock(null, "Second out check");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("OUT_OF_STOCK");
        assertThat(t.getOutCount()).isEqualTo(0);
        assertThat(t.getAlertMessageId()).isNull();
        assertThat(telegramClient.editedMessages).hasSize(1);
        assertThat(telegramClient.editedMessages.get(0).messageId()).isEqualTo(42L);
        assertThat(telegramClient.editedMessages.get(0).newText()).isEqualTo("🔴 Sold out: Test Product");
    }

    @Test
    @DisplayName("IN_STOCK result after 1 OUT_OF_STOCK check resets outCount to 0")
    void inStockResetsOutCount() {
        Target t = createTarget("IN_STOCK", 42L, 1);
        Snapshot snapshot = Snapshot.inStock(new BigDecimal("1000"), "Back in stock");

        monitoringService.applyTransition(t, snapshot);

        assertThat(t.getStatus()).isEqualTo("IN_STOCK");
        assertThat(t.getOutCount()).isEqualTo(0);
        assertThat(t.getAlertMessageId()).isEqualTo(42L);
        assertThat(telegramClient.deletedMessageIds).isEmpty();
    }

    @Test
    @DisplayName("pollDueTargets processes all due targets and updates nextCheckAt")
    void pollDueTargetsProcessesAllDue() {
        Target t1 = createTarget("OUT_OF_STOCK", null, 0);
        Target t2 = createTarget("IN_STOCK", 100L, 0);
        when(targetRepository.findByActiveTrueAndNextCheckAtBefore(any())).thenReturn(List.of(t1, t2));
        storeMonitor.nextSnapshot = Snapshot.inStock(new BigDecimal("999.00"), "Available");

        monitoringService.pollDueTargets();

        verify(targetRepository, times(2)).save(any(Target.class));
        assertThat(t1.getStatus()).isEqualTo("IN_STOCK");
        assertThat(t1.getLastCheckedAt()).isNotNull();
        assertThat(t1.getNextCheckAt()).isAfter(t1.getLastCheckedAt());
    }

    // --- Fakes ---

    static class FakeTelegramClient implements TelegramClient {
        record Sent(long chatId, String text, long messageId) {}
        record Edited(long chatId, long messageId, String newText) {}

        List<Sent> sentMessages = new ArrayList<>();
        List<Long> deletedMessageIds = new ArrayList<>();
        List<Edited> editedMessages = new ArrayList<>();
        boolean deleteSucceeds = true;
        private long idGen = 100L;

        @Override
        public Long send(long chatId, String text) {
            long id = ++idGen;
            sentMessages.add(new Sent(chatId, text, id));
            return id;
        }

        @Override
        public boolean delete(long chatId, long messageId) {
            if (deleteSucceeds) {
                deletedMessageIds.add(messageId);
                return true;
            }
            return false;
        }

        @Override
        public boolean edit(long chatId, long messageId, String text) {
            editedMessages.add(new Edited(chatId, messageId, text));
            return true;
        }

        @Override
        public List<TelegramUpdate> getUpdates(long offset, int timeoutSeconds) {
            return Collections.emptyList();
        }
    }

    static class FakeStoreMonitor implements StoreMonitor {
        private final String storeCode;
        Snapshot nextSnapshot = Snapshot.unknown("default");

        FakeStoreMonitor(String storeCode) {
            this.storeCode = storeCode;
        }

        @Override
        public String storeCode() {
            return storeCode;
        }

        @Override
        public Snapshot check(Target target) {
            return nextSnapshot;
        }
    }
}
