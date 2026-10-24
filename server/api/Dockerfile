FROM node:22-alpine
WORKDIR /app
COPY package.json ./
COPY server.js ./
COPY lib ./lib
ENV NODE_ENV=production
EXPOSE 8080
USER node
CMD ["node", "server.js"]
