package com.restockradar.telegram;

import com.restockradar.config.MonitoringProperties;
import com.restockradar.config.TelegramProperties;
import com.restockradar.monitoring.Availability;
import com.restockradar.monitoring.MonitoringService;
import com.restockradar.monitoring.Snapshot;
import com.restockradar.monitoring.adapters.GamesTheShopMonitor;
import com.restockradar.target.Target;
import com.restockradar.target.TargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BotCommandHandlerTest {

    private TargetRepository targetRepository;
    private MonitoringService monitoringService;
    private TelegramClient telegramClient;
    private TelegramProperties telegramProperties;
    private MonitoringProperties monitoringProperties;
    private BotCommandHandler handler;

    private static final long OWNER_ID = 555L;

    @BeforeEach
    void setUp() {
        targetRepository = mock(TargetRepository.class);
        monitoringService = mock(MonitoringService.class);
        telegramClient = mock(TelegramClient.class);

        telegramProperties = new TelegramProperties();
        telegramProperties.setBotToken("token");
        telegramProperties.setOwnerChatId(OWNER_ID);

        monitoringProperties = new MonitoringProperties();
        monitoringProperties.setDefaultIntervalSeconds(60);
        monitoringProperties.setRooterIntervalSeconds(15);
        monitoringProperties.setMinIntervalSeconds(10);

        when(monitoringService.getMonitors()).thenReturn(Map.of(
                "GAMES_THE_SHOP", mock(GamesTheShopMonitor.class)
        ));

        handler = new BotCommandHandler(
                targetRepository,
                monitoringService,
                telegramClient,
                telegramProperties,
                monitoringProperties
        );
    }

    @Test
    @DisplayName("Ignores updates from non-owner chat ID")
    void ignoresUnauthorizedUsers() {
        TelegramUpdate strangerUpdate = new TelegramUpdate(1L, 10L, 9999L, "/list", "stranger");
        handler.handleUpdate(strangerUpdate);

        verifyNoInteractions(telegramClient);
        verifyNoInteractions(targetRepository);
    }

    @Test
    @DisplayName("/help returns command list")
    void handleHelpCommand() {
        String reply = handler.processCommand("/help");
        assertThat(reply).contains("/add").contains("/list").contains("/check");
    }

    @Test
    @DisplayName("/add command creates new target with valid inputs")
    void handleAddSuccess() {
        when(targetRepository.save(any(Target.class))).thenAnswer(i -> {
            Target t = i.getArgument(0);
            t.setId(101L);
            return t;
        });

        String reply = handler.processCommand("/add GAMES_THE_SHOP https://www.gamestheshop.com/product/1 PSN 1000");

        assertThat(reply).contains("✅ Target #101 added successfully!");
        ArgumentCaptor<Target> captor = ArgumentCaptor.forClass(Target.class);
        verify(targetRepository).save(captor.capture());

        Target saved = captor.getValue();
        assertThat(saved.getStoreCode()).isEqualTo("GAMES_THE_SHOP");
        assertThat(saved.getUrl()).isEqualTo("https://www.gamestheshop.com/product/1");
        assertThat(saved.getName()).isEqualTo("PSN 1000");
        assertThat(saved.getIntervalSeconds()).isEqualTo(60);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    @DisplayName("/add command assigns 15s interval specifically for ROOTER store")
    void handleAddRooterGets15Seconds() {
        when(targetRepository.save(any(Target.class))).thenAnswer(i -> {
            Target t = i.getArgument(0);
            t.setId(102L);
            return t;
        });

        String reply = handler.processCommand("/add ROOTER https://rooter.gg/shop/diamonds Diamonds 100");

        assertThat(reply).contains("✅ Target #102 added successfully!");
        ArgumentCaptor<Target> captor = ArgumentCaptor.forClass(Target.class);
        verify(targetRepository).save(captor.capture());

        Target saved = captor.getValue();
        assertThat(saved.getStoreCode()).isEqualTo("ROOTER");
        assertThat(saved.getIntervalSeconds()).isEqualTo(15);
    }

    @Test
    @DisplayName("/add command rejects invalid or disallowed URL")
    void handleAddInvalidUrl() {
        String reply = handler.processCommand("/add GAMES_THE_SHOP http://insecure.com/product PSN 1000");
        assertThat(reply).contains("❌ URL validation failed");
        verify(targetRepository, never()).save(any());
    }

    @Test
    @DisplayName("/pause and /resume toggle target active status")
    void handlePauseAndResume() {
        Target t = new Target("GAMES_THE_SHOP", "https://www.gamestheshop.com/product/1", "Test", 600);
        t.setId(1L);
        when(targetRepository.findById(1L)).thenReturn(Optional.of(t));

        String pauseReply = handler.processCommand("/pause 1");
        assertThat(pauseReply).contains("paused");
        assertThat(t.isActive()).isFalse();

        String resumeReply = handler.processCommand("/resume 1");
        assertThat(resumeReply).contains("resumed");
        assertThat(t.isActive()).isTrue();
    }

    @Test
    @DisplayName("/interval updates target interval when above minimum")
    void handleInterval() {
        Target t = new Target("GAMES_THE_SHOP", "https://www.gamestheshop.com/product/1", "Test", 600);
        t.setId(1L);
        when(targetRepository.findById(1L)).thenReturn(Optional.of(t));

        String reply = handler.processCommand("/interval 1 900");
        assertThat(reply).contains("interval set to 900 seconds");
        assertThat(t.getIntervalSeconds()).isEqualTo(900);

        String tooLowReply = handler.processCommand("/interval 1 5");
        assertThat(tooLowReply).contains("Interval cannot be less than 10");
    }

    @Test
    @DisplayName("/remove deactivates target and deletes active alert message")
    void handleRemoveCleansAlert() {
        Target t = new Target("GAMES_THE_SHOP", "https://www.gamestheshop.com/product/1", "Test", 600);
        t.setId(1L);
        t.setAlertMessageId(777L);
        when(targetRepository.findById(1L)).thenReturn(Optional.of(t));

        String reply = handler.processCommand("/remove 1");
        assertThat(reply).contains("deactivated and alerts cleared");
        verify(telegramClient).delete(OWNER_ID, 777L);
        assertThat(t.isActive()).isFalse();
        assertThat(t.getAlertMessageId()).isNull();
    }

    @Test
    @DisplayName("/check runs immediate check via monitoringService")
    void handleCheck() {
        Target t = new Target("GAMES_THE_SHOP", "https://www.gamestheshop.com/product/1", "PSN Card", 600);
        t.setId(5L);
        when(targetRepository.findById(5L)).thenReturn(Optional.of(t));
        when(monitoringService.checkTargetNow(5L))
                .thenReturn(Snapshot.inStock(new BigDecimal("1000"), "Found button"));

        String reply = handler.processCommand("/check 5");
        assertThat(reply).contains("IN_STOCK").contains("₹1000").contains("Found button");
    }
}
