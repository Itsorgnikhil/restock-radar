# Restock Radar 📡

A lightweight, Telegram-only stock monitoring bot built with **Java 21** and **Spring Boot 3.3.x**.

Restock Radar monitors product availability (initially PlayStation Store Gift Cards) across Indian e-commerce sites and is controlled entirely via Telegram. When a product comes into stock, the bot sends an alert. When it sells out, the bot deletes the alert message (or edits it to "🔴 Sold out" if deletion fails after 48 hours).

There is **no** web dashboard, **no** external database server (uses embedded file-based H2), **no** Redis, and **no** multi-user complexity. It is an efficient, single-owner utility.

---

## Architecture & Features

- **Runtime**: Java 21 LTS, Spring Boot 3.3.x, Spring Data JPA, H2 file database (`./data/radar`).
- **Parsing**: Schema.org JSON-LD extraction (`<script type="application/ld+json">`) with fallback to static HTML inspection via Jsoup.
- **Telegram Polling**: Background long-polling (`getUpdates` with `timeout=25` and offset tracking).
- **Security**:
  - Updates from any chat ID other than the configured owner are ignored immediately.
  - SSRF protection: only HTTPS URLs, strict per-store domain whitelist, rejection of private/link-local/localhost IPs.
  - Safe bot behavior: honest User-Agent (`RestockRadar/1.0 (personal use)`), 10s request timeout, respects rate limits, never tries to bypass CAPTCHAs.
- **State Machine**:
  - Prevents duplicate alerts for already in-stock items.
  - Non-200, timeout, or parsing failures return `UNKNOWN` and **never** trigger out-of-stock actions.
  - 403/429 or robot checks return `BLOCKED`. A target blocked for 5+ consecutive checks notifies the owner once.
  - Out-of-stock transitions require 2 consecutive confirmations before deleting/editing alert messages.

---

## Telegram Bot Setup

### 1. Create Bot with @BotFather
1. Open Telegram and search for [@BotFather](https://t.me/BotFather).
2. Send `/newbot` and follow the prompts to choose a name and username (e.g., `my_restock_radar_bot`).
3. BotFather will provide your **HTTP API Token** (e.g. `7123456789:AAH...`).
4. Save this as `TELEGRAM_BOT_TOKEN`.

### 2. Find Your Telegram Chat ID
1. Search for [@userinfobot](https://t.me/userinfobot) on Telegram and press **Start**.
2. The bot will reply with your numeric **Id** (e.g., `987654321`).
3. Save this as `TELEGRAM_CHAT_ID`.
4. Send a `/start` message to your newly created bot so it has permission to message you.

---

## Configuration

Copy `.env.example` to `.env`:

```bash
cp .env.example .env
```

Edit `.env` with your credentials:

```dotenv
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
TELEGRAM_CHAT_ID=987654321
```

### Application Properties (`application.yml`)

| Property | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:h2:file:./data/radar` | H2 file-based persistent database path |
| `telegram.bot-token` | `${TELEGRAM_BOT_TOKEN}` | Telegram Bot API token |
| `telegram.owner-chat-id` | `${TELEGRAM_CHAT_ID}` | Chat ID authorized to control the bot |
| `monitoring.default-interval-seconds` | `600` | Default poll interval per product (10 mins) |
| `monitoring.min-interval-seconds` | `300` | Minimum allowed polling interval (5 mins) |
| `monitoring.request-spacing-ms` | `2000` | Delay between consecutive HTTP requests (2s) |
| `monitoring.out-of-stock-confirmations`| `2` | Consecutive out checks before alert deletion |
| `monitoring.alert-on-first-check` | `true` | Send alert if in stock on the very first check |

---

## Running Locally

### Prerequisites
- JDK 21 or higher
- Apache Maven 3.8+

### Build and Test
```bash
mvn clean test
```

### Run
```bash
export TELEGRAM_BOT_TOKEN="your_bot_token"
export TELEGRAM_CHAT_ID="your_chat_id"
mvn spring-boot:run
```
*(On Windows PowerShell: `$env:TELEGRAM_BOT_TOKEN="your_token"`, `$env:TELEGRAM_CHAT_ID="your_id"`)*

The database will be created in `./data/radar.mv.db`.

---

## Running with Docker

### Docker Compose (Recommended)
Make sure `.env` is populated in the current directory:

```bash
docker compose up -d --build
```

View logs:
```bash
docker compose logs -f
```

Stop the container:
```bash
docker compose down
```

The `./data` directory on the host is mounted into `/app/data` in the container, persisting all targets and state across container restarts.

---

## Telegram Commands Reference

| Command | Arguments | Description |
|---|---|---|
| `/add` | `<STORE_CODE> <url> <name...>` | Add a product target to monitor |
| `/list` | none | Display all monitored targets with current status |
| `/status` | `<id>` | View details, last check time, price, and decision evidence |
| `/check` | `<id>` | Perform an immediate on-demand check for a target |
| `/pause` | `<id>` | Temporarily pause scheduled checks for a target |
| `/resume` | `<id>` | Resume scheduled checks for a target |
| `/remove` | `<id>` | Deactivate target and delete any active alert message |
| `/interval` | `<id> <seconds>` | Update check interval (enforces 300s minimum) |
| `/stores` | none | List supported store codes and adapter status |
| `/help` | none | Display help message and command syntax |

### Example Usage:
```
/add GAMES_THE_SHOP https://www.gamestheshop.com/product/f528de33-a760-4fea-9b04-3d150b24a116?variant=7e6631a0-0d65-4907-89b7-4ab5e24397cc PlayStation Gift Card 1000
```

When stock is detected, you will receive:
```
🟢 IN STOCK
PlayStation Gift Card 1000
Store: Games The Shop
Price: ₹1000
https://www.gamestheshop.com/product/...
```

---

## State Transition Rules

| Check Result | Previous Status | Engine Action |
|---|---|---|
| `UNKNOWN` / `BLOCKED` | Any | No state change. Updates `lastCheckedAt` and `lastEvidence`. Warns owner if `BLOCKED` for 5 consecutive checks. |
| `IN_STOCK` | `null` (first check) | Sends alert if `monitoring.alert-on-first-check=true`. Saves `alertMessageId`, status `IN_STOCK`. |
| `IN_STOCK` | `OUT_OF_STOCK` | Sends alert, saves `alertMessageId`, sets status `IN_STOCK`, resets `outCount`. |
| `IN_STOCK` | `IN_STOCK` | No action (prevents duplicate alerts). Resets `outCount` to 0. |
| `OUT_OF_STOCK` | `null` (first check) | Sets baseline `OUT_OF_STOCK`, no alert message. |
| `OUT_OF_STOCK` | `OUT_OF_STOCK` | No action. |
| `OUT_OF_STOCK` | `IN_STOCK` | Increments `outCount`. When `outCount >= 2`, deletes alert via `deleteMessage`. If delete fails, edits message to `"🔴 Sold out: <name>"`. Resets `outCount`, sets status `OUT_OF_STOCK`. |

---

## How to Write a New Store Adapter

1. **Add Allowed Domain in `UrlValidator`**:
   Open `com.restockradar.util.UrlValidator` and add the store's domain to `ALLOWED_DOMAINS`:
   ```java
   "NEW_STORE", Set.of("newstore.in", "www.newstore.in")
   ```

2. **Implement `StoreMonitor` or Extend `JsonLdMonitor`**:
   Create a new class under `com.restockradar.monitoring.adapters`, annotating it with `@Component`:

   ```java
   package com.restockradar.monitoring.adapters;

   import com.fasterxml.jackson.databind.ObjectMapper;
   import com.restockradar.monitoring.Availability;
   import com.restockradar.monitoring.JsonLdMonitor;
   import com.restockradar.monitoring.Snapshot;
   import com.restockradar.target.Target;
   import org.jsoup.Connection;
   import org.jsoup.nodes.Document;
   import org.springframework.stereotype.Component;

   @Component
   public class NewStoreMonitor extends JsonLdMonitor {

       public static final String STORE_CODE = "NEW_STORE";

       public NewStoreMonitor(ObjectMapper objectMapper) {
           super(objectMapper);
       }

       @Override
       public String storeCode() {
           return STORE_CODE;
       }

       @Override
       public Snapshot fallbackCheck(Document document, Connection.Response response, Target target) {
           // If JSON-LD is missing, inspect HTML elements
           if (!document.select("button.add-to-cart:not([disabled])").isEmpty()) {
               return Snapshot.inStock(extractPrice(document), "HTML: Add to Cart button active");
           }
           if (!document.select(".sold-out-badge").isEmpty()) {
               return Snapshot.outOfStock(null, "HTML: Sold out badge present");
           }
           return Snapshot.unknown("Could not determine stock status");
       }
   }
   ```

3. **Key Adapter Principles**:
   - Return `Availability.BLOCKED` on HTTP 403, 429, or CAPTCHA pages. **Never** attempt to bypass protections or rotate proxies.
   - Return `Availability.UNKNOWN` on network errors, timeouts, or unparseable pages. **Never** treat an inaccessible page as `OUT_OF_STOCK`.
   - Never log cookies, authentication headers, or bot tokens.
