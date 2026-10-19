<template>
  <div class="page">
    <h1 class="page-title">团队成员</h1>
    <p class="page-subtitle">共 {{ store.members.length }} 位成员</p>

    <!-- 负责人单独占一行，信息量本来就比其他人多 -->
    <article v-if="lead" class="lead-card">
      <div class="lead-avatar">
        <img v-if="lead.photoPath" :src="lead.photoPath" :alt="lead.name" />
        <span v-else>{{ lead.name ? lead.name.charAt(0) : '?' }}</span>
      </div>
      <div class="lead-body">
        <h2>{{ lead.name }}</h2>
        <p class="lead-role">
          <span class="chip">{{ lead.role }}</span>
          <span v-if="lead.title">{{ lead.title }}</span>
        </p>
        <p class="lead-bio">{{ lead.bio }}</p>
        <a v-if="lead.email" :href="`mailto:${lead.email}`" class="email">{{ lead.email }}</a>
      </div>
    </article>

    <div v-if="others.length" class="member-grid">
      <article v-for="member in others" :key="member.id" class="member-card">
        <div class="avatar">
          <img v-if="member.photoPath" :src="member.photoPath" :alt="member.name" />
          <span v-else>{{ member.name ? member.name.charAt(0) : '?' }}</span>
        </div>
        <h3>{{ member.name }}</h3>
        <p class="role">
          <span class="chip">{{ member.role }}</span>
          <span v-if="member.title">{{ member.title }}</span>
        </p>
        <p class="bio">{{ member.bio }}</p>
        <a v-if="member.email" :href="`mailto:${member.email}`" class="email">{{ member.email }}</a>
      </article>
    </div>

    <p v-if="!store.members.length" class="empty">成员信息待补充</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { store } from '../store'

const lead = computed(() => store.members.find((item) => item.sortOrder === 0) || null)
const others = computed(() => store.members.filter((item) => item !== lead.value))
</script>

<style scoped>
.lead-card {
  display: flex;
  gap: 26px;
  background: #fff;
  border: 1px solid var(--lab-border);
  border-radius: var(--lab-radius);
  padding: 28px 30px;
  box-shadow: var(--lab-shadow);
  margin-bottom: 20px;
}

.lead-avatar {
  flex: none;
  width: 116px;
  height: 116px;
  border-radius: 18px;
  overflow: hidden;
  background: var(--lab-primary-soft);
  color: var(--lab-primary);
  font-size: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.lead-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.lead-body {
  min-width: 0;
}

.lead-body h2 {
  margin: 0 0 8px;
  font-size: 22px;
}

.lead-role {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 0 0 12px;
  color: var(--lab-muted);
  font-size: 14px;
}

.lead-bio {
  margin: 0 0 12px;
  color: #333;
  font-size: 14.5px;
}

.member-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(270px, 1fr));
  gap: 16px;
}

.member-card {
  background: #fff;
  border: 1px solid var(--lab-border);
  border-radius: var(--lab-radius);
  padding: 22px;
  box-shadow: var(--lab-shadow);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}

.member-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--lab-shadow-hover);
  border-color: #c9dcf5;
}

.avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  overflow: hidden;
  background: var(--lab-primary-soft);
  color: var(--lab-primary);
  font-size: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 14px;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.member-card h3 {
  margin: 0 0 6px;
  font-size: 17px;
}

.role {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 0 0 10px;
  color: var(--lab-muted);
  font-size: 13px;
}

.bio {
  margin: 0 0 10px;
  font-size: 14px;
  color: #444;
  line-height: 1.7;
}

.email {
  font-size: 13px;
  color: var(--lab-muted);
  text-decoration: none;
  word-break: break-all;
}

.email:hover {
  color: var(--lab-primary);
}

@media (max-width: 640px) {
  .lead-card {
    flex-direction: column;
    align-items: center;
    text-align: center;
    padding: 24px 20px;
    gap: 16px;
  }

  .lead-role {
    justify-content: center;
  }
}
</style>
