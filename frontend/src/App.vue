<template>
  <div class="page">
    <header class="header">
      <h1>用户管理</h1>
      <p class="sub">Spring Boot 3 + Vue 3 · MySQL 主从读写分离 · Redis 缓存</p>
    </header>
    <UserList />
  </div>
</template>

<script setup>
import UserList from './views/UserList.vue'
</script>

<style>
body {
  margin: 0;
  background: #f5f7fa;
  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
}
.page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px 16px 48px;
}
.header h1 {
  margin: 0 0 6px;
  font-size: 24px;
  color: #1f2d3d;
}
.header .sub {
  margin: 0 0 20px;
  color: #6b7a8d;
  font-size: 13px;
}
</style>