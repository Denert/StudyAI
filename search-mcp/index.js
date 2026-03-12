#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";

const server = new Server(
  { name: "search-mcp", version: "1.0.0" },
  { capabilities: { tools: {} } }
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "search",
      description: "Search for information on the internet. Returns relevant text content about the query.",
      inputSchema: {
        type: "object",
        properties: {
          query: { type: "string", description: "Search query (e.g., 'Kotlin programming language')" }
        },
        required: ["query"]
      }
    }
  ]
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  if (name === "search") {
    const { query } = args;

    if (!query) {
      return { content: [{ type: "text", text: "Error: query is required" }], isError: true };
    }

    try {
      // Use DuckDuckGo Instant Answer API
      const url = `https://api.duckduckgo.com/?q=${encodeURIComponent(query)}&format=json&no_html=1`;
      const res = await fetch(url);
      const data = await res.json();

      let result = "";

      // Abstract (main answer)
      if (data.Abstract) {
        result += `## ${data.Heading || query}\n\n${data.Abstract}\n\n`;
        if (data.AbstractSource) {
          result += `Source: ${data.AbstractSource}\n\n`;
        }
      }

      // Related topics
      if (data.RelatedTopics && data.RelatedTopics.length > 0) {
        result += "## Related Information\n\n";
        for (const topic of data.RelatedTopics.slice(0, 5)) {
          if (topic.Text) {
            result += `- ${topic.Text}\n`;
          }
        }
      }

      // If no results, try Wikipedia API
      if (!result) {
        const wikiUrl = `https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(query)}`;
        const wikiRes = await fetch(wikiUrl);

        if (wikiRes.ok) {
          const wikiData = await wikiRes.json();
          if (wikiData.extract) {
            result = `## ${wikiData.title}\n\n${wikiData.extract}\n\nSource: Wikipedia`;
          }
        }
      }

      if (!result) {
        result = `No results found for "${query}". Try a different search query.`;
      }

      return { content: [{ type: "text", text: result }] };
    } catch (error) {
      return { content: [{ type: "text", text: `Search error: ${error.message}` }], isError: true };
    }
  }

  return { content: [{ type: "text", text: `Unknown tool: ${name}` }], isError: true };
});

const transport = new StdioServerTransport();
await server.connect(transport);
console.error("Search MCP Server running");
