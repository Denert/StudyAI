#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { WebSocketServer } from "ws";
import { createServer } from "http";
import { readFileSync, writeFileSync, existsSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = join(__dirname, "config.json");
const WS_PORT = 8081;
const HTTP_PORT = 8082;

// Weather code descriptions
const weatherCodes = {
  0: "Clear sky", 1: "Mainly clear", 2: "Partly cloudy", 3: "Overcast",
  45: "Foggy", 48: "Rime fog", 51: "Light drizzle", 53: "Drizzle", 55: "Dense drizzle",
  61: "Slight rain", 63: "Rain", 65: "Heavy rain",
  71: "Slight snow", 73: "Snow", 75: "Heavy snow",
  80: "Rain showers", 81: "Moderate showers", 82: "Violent showers",
  95: "Thunderstorm", 96: "Thunderstorm with hail", 99: "Severe thunderstorm",
};

// ============ Config Management ============

function loadConfig() {
  try {
    if (existsSync(CONFIG_PATH)) {
      return JSON.parse(readFileSync(CONFIG_PATH, "utf-8"));
    }
  } catch (e) {
    console.error("Error loading config:", e.message);
  }
  return { enabled: false, city: null, interval_minutes: 30 };
}

function saveConfig(config) {
  writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2));
}

// ============ Weather API ============

async function fetchWeather(city) {
  const geoUrl = `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1&language=en&format=json`;
  const geoRes = await fetch(geoUrl);
  const geoData = await geoRes.json();

  if (!geoData.results || geoData.results.length === 0) {
    throw new Error(`City "${city}" not found`);
  }

  const { latitude, longitude, name: cityName, country } = geoData.results[0];

  const weatherUrl = `https://api.open-meteo.com/v1/forecast?latitude=${latitude}&longitude=${longitude}&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m`;
  const weatherRes = await fetch(weatherUrl);
  const weatherData = await weatherRes.json();
  const current = weatherData.current;

  return {
    location: `${cityName}, ${country}`,
    temperature: `${current.temperature_2m}°C`,
    feels_like: `${current.apparent_temperature}°C`,
    conditions: weatherCodes[current.weather_code] || "Unknown",
    humidity: `${current.relative_humidity_2m}%`,
    wind: `${current.wind_speed_10m} km/h`,
    timestamp: new Date().toISOString(),
  };
}

function formatWeatherMessage(weather) {
  return `🌤 Прогноз погоды
━━━━━━━━━━━━━━━━━━━━━
📍 ${weather.location}
🌡 ${weather.temperature} (ощущается ${weather.feels_like})
☁️ ${weather.conditions}
💨 ${weather.wind}
💧 ${weather.humidity}`;
}

// ============ Mode Detection ============

let isMainServer = false;

async function checkIfMainServer() {
  return new Promise((resolve) => {
    fetch(`http://localhost:${HTTP_PORT}/ping`)
      .then((res) => {
        if (res.ok) {
          resolve(false); // Another server is running
        } else {
          resolve(true);
        }
      })
      .catch(() => {
        resolve(true); // No server running, we become main
      });
  });
}

// ============ WebSocket Server (Main Server Only) ============

let wss = null;
const clients = new Set();

function initWebSocketServer() {
  wss = new WebSocketServer({ port: WS_PORT });

  wss.on("connection", (ws) => {
    clients.add(ws);
    console.error(`[WS] Client connected. Total: ${clients.size}`);

    ws.on("close", () => {
      clients.delete(ws);
      console.error(`[WS] Client disconnected. Total: ${clients.size}`);
    });

    ws.on("error", (err) => {
      console.error("[WS] Error:", err.message);
      clients.delete(ws);
    });
  });

  console.error(`[WS] WebSocket server running on port ${WS_PORT}`);
}

function broadcast(message) {
  if (!isMainServer) return;

  const payload = JSON.stringify(message);
  for (const client of clients) {
    if (client.readyState === 1) {
      client.send(payload);
    }
  }
}

// ============ HTTP API Server (Main Server Only) ============

let httpServer = null;

function initHttpServer() {
  httpServer = createServer(async (req, res) => {
    res.setHeader("Content-Type", "application/json");

    const url = new URL(req.url, `http://localhost:${HTTP_PORT}`);
    const path = url.pathname;

    try {
      if (path === "/ping") {
        res.writeHead(200);
        res.end(JSON.stringify({ status: "ok", mode: "main" }));
        return;
      }

      if (path === "/set_schedule" && req.method === "POST") {
        let body = "";
        for await (const chunk of req) body += chunk;
        const { city, interval_minutes } = JSON.parse(body);

        await fetchWeather(city); // Validate city

        const config = { enabled: true, city, interval_minutes };
        saveConfig(config);
        startScheduler();

        res.writeHead(200);
        res.end(JSON.stringify({ success: true, config }));
        return;
      }

      if (path === "/get_schedule") {
        const config = loadConfig();
        res.writeHead(200);
        res.end(JSON.stringify({ success: true, config }));
        return;
      }

      if (path === "/stop_schedule" && req.method === "POST") {
        const config = loadConfig();
        config.enabled = false;
        saveConfig(config);
        stopScheduler();

        res.writeHead(200);
        res.end(JSON.stringify({ success: true }));
        return;
      }

      if (path === "/get_forecast") {
        const city = url.searchParams.get("city");
        const weather = await fetchWeather(city);
        res.writeHead(200);
        res.end(JSON.stringify({ success: true, weather, formatted: formatWeatherMessage(weather) }));
        return;
      }

      res.writeHead(404);
      res.end(JSON.stringify({ error: "Not found" }));
    } catch (err) {
      res.writeHead(500);
      res.end(JSON.stringify({ error: err.message }));
    }
  });

  httpServer.listen(HTTP_PORT, () => {
    console.error(`[HTTP] API server running on port ${HTTP_PORT}`);
  });
}

// ============ Scheduler (Main Server Only) ============

let schedulerInterval = null;

function startScheduler() {
  stopScheduler();

  const config = loadConfig();
  if (!config.enabled || !config.city || !config.interval_minutes) {
    return;
  }

  const intervalMs = config.interval_minutes * 60 * 1000;

  console.error(`[Scheduler] Starting: ${config.city} every ${config.interval_minutes} min`);

  schedulerInterval = setInterval(async () => {
    try {
      const weather = await fetchWeather(config.city);
      const message = formatWeatherMessage(weather);

      broadcast({ type: "weather", message, data: weather });

      console.error(`[Scheduler] Sent weather for ${config.city}`);
    } catch (err) {
      console.error(`[Scheduler] Error:`, err.message);
    }
  }, intervalMs);

  // Send immediately
  (async () => {
    try {
      const weather = await fetchWeather(config.city);
      const message = formatWeatherMessage(weather);
      broadcast({ type: "weather", message, data: weather });
      console.error(`[Scheduler] Initial weather sent for ${config.city}`);
    } catch (err) {
      console.error(`[Scheduler] Initial error:`, err.message);
    }
  })();
}

function stopScheduler() {
  if (schedulerInterval) {
    clearInterval(schedulerInterval);
    schedulerInterval = null;
    console.error("[Scheduler] Stopped");
  }
}

// ============ HTTP Client (Secondary Server) ============

async function callMainServer(path, method = "GET", body = null) {
  const url = `http://localhost:${HTTP_PORT}${path}`;
  const options = { method };

  if (body) {
    options.headers = { "Content-Type": "application/json" };
    options.body = JSON.stringify(body);
  }

  const res = await fetch(url, options);
  return res.json();
}

// ============ MCP Server ============

const server = new Server(
  { name: "weather-scheduler-mcp", version: "1.0.0" },
  { capabilities: { tools: {} } }
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "set_schedule",
      description: "Set up scheduled weather notifications. Specify city and interval in minutes.",
      inputSchema: {
        type: "object",
        properties: {
          city: { type: "string", description: "City name (e.g., 'Moscow', 'London')" },
          interval_minutes: { type: "number", description: "Notification interval in minutes (e.g., 30, 60)" },
        },
        required: ["city", "interval_minutes"],
      },
    },
    {
      name: "get_schedule",
      description: "Get current weather notification schedule settings.",
      inputSchema: { type: "object", properties: {} },
    },
    {
      name: "stop_schedule",
      description: "Stop weather notifications.",
      inputSchema: { type: "object", properties: {} },
    },
    {
      name: "get_forecast",
      description: "Get current weather forecast for a city (one-time request).",
      inputSchema: {
        type: "object",
        properties: {
          city: { type: "string", description: "City name" },
        },
        required: ["city"],
      },
    },
  ],
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    if (name === "set_schedule") {
      const { city, interval_minutes } = args;

      if (!city || !interval_minutes) {
        return {
          content: [{ type: "text", text: "Error: city and interval_minutes are required" }],
          isError: true,
        };
      }

      if (isMainServer) {
        await fetchWeather(city);
        const config = { enabled: true, city, interval_minutes };
        saveConfig(config);
        startScheduler();
      } else {
        await callMainServer("/set_schedule", "POST", { city, interval_minutes });
      }

      return {
        content: [{
          type: "text",
          text: `✅ Расписание настроено!\n\n📍 Город: ${city}\n⏱ Интервал: каждые ${interval_minutes} минут\n\nПервое уведомление отправлено. Следующее через ${interval_minutes} минут.`,
        }],
      };
    }

    if (name === "get_schedule") {
      let config;
      if (isMainServer) {
        config = loadConfig();
      } else {
        const result = await callMainServer("/get_schedule");
        config = result.config;
      }

      if (!config.enabled) {
        return {
          content: [{ type: "text", text: "📭 Уведомления о погоде не настроены." }],
        };
      }

      return {
        content: [{
          type: "text",
          text: `📋 Текущие настройки:\n\n📍 Город: ${config.city}\n⏱ Интервал: каждые ${config.interval_minutes} минут\n✅ Статус: активно`,
        }],
      };
    }

    if (name === "stop_schedule") {
      if (isMainServer) {
        const config = loadConfig();
        config.enabled = false;
        saveConfig(config);
        stopScheduler();
      } else {
        await callMainServer("/stop_schedule", "POST");
      }

      return {
        content: [{ type: "text", text: "⏹ Уведомления о погоде остановлены." }],
      };
    }

    if (name === "get_forecast") {
      const { city } = args;

      if (!city) {
        return {
          content: [{ type: "text", text: "Error: city is required" }],
          isError: true,
        };
      }

      let formatted;
      if (isMainServer) {
        const weather = await fetchWeather(city);
        formatted = formatWeatherMessage(weather);
      } else {
        const result = await callMainServer(`/get_forecast?city=${encodeURIComponent(city)}`);
        formatted = result.formatted;
      }

      return {
        content: [{ type: "text", text: formatted }],
      };
    }

    return {
      content: [{ type: "text", text: `Unknown tool: ${name}` }],
      isError: true,
    };
  } catch (error) {
    return {
      content: [{ type: "text", text: `Error: ${error.message}` }],
      isError: true,
    };
  }
});

// ============ Main ============

async function main() {
  // Check if another server is already running
  isMainServer = await checkIfMainServer();

  if (isMainServer) {
    console.error("[Mode] Starting as MAIN server (WebSocket + HTTP + Scheduler)");
    initWebSocketServer();
    initHttpServer();
    startScheduler();
  } else {
    console.error("[Mode] Starting as SECONDARY (MCP only, proxying to main server)");
  }

  // Start MCP server
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("Weather Scheduler MCP Server running on stdio");
}

main().catch((error) => {
  console.error("Server error:", error);
  process.exit(1);
});
