FROM node:22-alpine
WORKDIR /service
COPY package.json domain.mjs server.mjs ./
USER node
ENV NODE_ENV=production
CMD ["node", "server.mjs"]
