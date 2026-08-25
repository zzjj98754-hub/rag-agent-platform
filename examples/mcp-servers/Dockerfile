FROM node:22-alpine
WORKDIR /srv/mcp
COPY package.json ./
RUN npm install --omit=dev && npm cache clean --force
COPY stdio-server.js streamable-http-server.js ./
USER node
EXPOSE 3001
CMD ["node", "streamable-http-server.js"]
