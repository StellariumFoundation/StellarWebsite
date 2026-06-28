<script lang="ts">
  import { Home, Book, PlayCircle, FileQuestion, CircleDollarSign, Mail } from 'lucide-svelte';
  import HomeScreen from './screens/HomeScreen.svelte';
  import LibraryScreen from './screens/LibraryScreen.svelte';
  import MediaScreen from './screens/MediaScreen.svelte';
  import QuizScreen from './screens/QuizScreen.svelte';
  import SponsorScreen from './screens/SponsorScreen.svelte';
  import ContactScreen from './screens/ContactScreen.svelte';
  import AudioControl from './components/AudioControl.svelte';

  let location = $state(window.location.pathname);

  async function setupStatusBar() {
    try {
      const { StatusBar, Style } = await import('@capacitor/status-bar');
      await StatusBar.setStyle({ style: Style.Dark });
      await StatusBar.setBackgroundColor({ color: '#0d0015' });
    } catch {
      // not running in Capacitor (web)
    }
  }

  function matchRoute(pattern: string): boolean {
    if (pattern === '/') return location === '/' || location === '';
    return location.startsWith(pattern);
  }

  function navigate(path: string) {
    window.history.pushState({}, '', path);
    location = path;
  }

  function isKnownRoute(path: string): boolean {
    if (path === '/' || path === '') return true;
    const knownPaths = ['/library', '/media', '/quiz', '/sponsor', '/contact'];
    return knownPaths.some(p => path.startsWith(p));
  }

  function bookFromPath(): string | null {
    const prefix = '/library/';
    if (location.startsWith(prefix)) {
      const slug = decodeURIComponent(location.slice(prefix.length));
      return slug || null;
    }
    return null;
  }

  $effect(() => {
    if (!isKnownRoute(location)) {
      window.location.replace('/');
      return;
    }

    setupStatusBar();

    const onPop = () => location = window.location.pathname;
    window.addEventListener('popstate', onPop);

    const timer = setTimeout(async () => {
      try {
        const fetchLit = fetch('/literature.json').then(async (res) => {
          if (res.ok) {
            const data = await res.json();
            localStorage.setItem('stellarium_literature_cache', JSON.stringify(data));
          }
        });
        const fetchQuiz = fetch('/quizzes.json').then(async (res) => {
          if (res.ok) {
            const data = await res.json();
            localStorage.setItem('stellarium_quizzes_cache', JSON.stringify(data));
          }
        });
        await Promise.allSettled([fetchLit, fetchQuiz]);
      } catch (err) {
        console.warn("Silent preheat failed or offline", err);
      }
    }, 1200);

    return () => {
      window.removeEventListener('popstate', onPop);
      clearTimeout(timer);
    };
  });

  const tabs = [
    { path: '/', icon: Home, label: 'Home' },
    { path: '/library', icon: Book, label: 'Library' },
    { path: '/media', icon: PlayCircle, label: 'Media' },
    { path: '/quiz', icon: FileQuestion, label: 'Quiz' },
    { path: '/sponsor', icon: CircleDollarSign, label: 'Sponsor' },
    { path: '/contact', icon: Mail, label: 'Contact' },
  ];
</script>

<div class="flex flex-col h-[100dvh] w-full bg-transparent">
  <div class="flex-1 overflow-hidden relative">
    <div class={matchRoute('/') && location === '/' ? 'h-full' : 'hidden'}><HomeScreen /></div>
    <div class={matchRoute('/library') ? 'h-full' : 'hidden'}><LibraryScreen bookTitle={bookFromPath()} /></div>
    <div class={matchRoute('/media') ? 'h-full' : 'hidden'}><MediaScreen /></div>
    <div class={matchRoute('/quiz') ? 'h-full' : 'hidden'}><QuizScreen /></div>
    <div class={matchRoute('/sponsor') ? 'h-full' : 'hidden'}><SponsorScreen onContact={() => navigate('/contact')} /></div>
    <div class={matchRoute('/contact') ? 'h-full' : 'hidden'}><ContactScreen /></div>
  </div>

  <AudioControl />

  <div class="bg-black/70 backdrop-blur-md border-t border-white/5 safe-bottom z-50">
    <div class="flex justify-around items-center h-16 md:h-20 lg:h-24 px-2 max-w-lg md:max-w-3xl lg:max-w-5xl mx-auto">
      {#each tabs as { path, icon: Icon, label }}
        <button
          onclick={() => navigate(path)}
          class="flex flex-col items-center justify-center w-full h-full space-y-1 transition-colors relative {matchRoute(path) ? 'text-[var(--color-tertiary)]' : 'text-gray-400 hover:text-white'}"
        >
          <div class="relative p-1 md:p-2 lg:p-3 rounded-full z-10">
            {#if matchRoute(path)}
              <div class="absolute inset-0 bg-[var(--color-tertiary)]/20 blur-md rounded-full" />
            {/if}
            <Icon strokeWidth={matchRoute(path) ? 2.5 : 2} class="relative z-10 w-[22px] h-[22px] md:w-[26px] md:h-[26px] lg:w-[32px] lg:h-[32px]" />
          </div>
          <span class="text-[10px] md:text-xs lg:text-sm font-medium tracking-wide">{label}</span>
        </button>
      {/each}
    </div>
  </div>
</div>