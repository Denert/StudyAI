#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";

const server = new Server(
  { name: "summarize-mcp", version: "1.0.0" },
  { capabilities: { tools: {} } }
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "summarize",
      description: "Summarize text by extracting key sentences. Good for condensing search results or long texts.",
      inputSchema: {
        type: "object",
        properties: {
          text: { type: "string", description: "Text to summarize" },
          max_sentences: { type: "number", description: "Maximum number of sentences in summary (default: 3)" }
        },
        required: ["text"]
      }
    }
  ]
}));

// Simple extractive summarization
function summarize(text, maxSentences = 3) {
  // Split into sentences
  const sentences = text
    .replace(/\n+/g, " ")
    .split(/(?<=[.!?])\s+/)
    .map(s => s.trim())
    .filter(s => s.length > 20);

  if (sentences.length <= maxSentences) {
    return sentences.join(" ");
  }

  // Score sentences by word frequency
  const wordFreq = {};
  const words = text.toLowerCase().match(/\b[a-zа-яё]+\b/g) || [];

  for (const word of words) {
    if (word.length > 3) {
      wordFreq[word] = (wordFreq[word] || 0) + 1;
    }
  }

  const scored = sentences.map((sentence, index) => {
    const sentenceWords = sentence.toLowerCase().match(/\b[a-zа-яё]+\b/g) || [];
    let score = 0;

    for (const word of sentenceWords) {
      score += wordFreq[word] || 0;
    }

    // Boost first sentences (usually contain key info)
    if (index < 2) score *= 1.5;

    return { sentence, score };
  });

  // Sort by score and take top sentences
  scored.sort((a, b) => b.score - a.score);

  // Get top sentences but maintain original order
  const topSentences = scored.slice(0, maxSentences);
  const originalOrder = sentences.filter(s => topSentences.some(t => t.sentence === s));

  return originalOrder.join(" ");
}

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  if (name === "summarize") {
    const { text, max_sentences = 3 } = args;

    if (!text) {
      return { content: [{ type: "text", text: "Error: text is required" }], isError: true };
    }

    try {
      const summary = summarize(text, max_sentences);

      const result = `## Summary\n\n${summary}\n\n---\nOriginal length: ${text.length} chars → Summary: ${summary.length} chars`;

      return { content: [{ type: "text", text: result }] };
    } catch (error) {
      return { content: [{ type: "text", text: `Summarize error: ${error.message}` }], isError: true };
    }
  }

  return { content: [{ type: "text", text: `Unknown tool: ${name}` }], isError: true };
});

const transport = new StdioServerTransport();
await server.connect(transport);
console.error("Summarize MCP Server running");
