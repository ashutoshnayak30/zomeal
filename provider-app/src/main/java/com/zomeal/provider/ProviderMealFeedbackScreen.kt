package com.zomeal.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import java.util.Locale

@Composable
fun ProviderMealFeedbackScreen(repository:SupabaseProviderRepository,onBack:()->Unit){
 var rows by remember{mutableStateOf(JSONArray())};var error by remember{mutableStateOf<String?>(null)}
 var offset by remember{mutableIntStateOf(0)};var refresh by remember{mutableIntStateOf(0)};var loading by remember{mutableStateOf(true)}
 LaunchedEffect(offset,refresh){loading=true;repository.mealFeedback(offset){r,e->loading=false;error=e;rows=r?.optJSONArray("items")?:JSONArray()}}
 BackHandler(onBack=onBack)
 Scaffold(containerColor=Color(0xFFF5FAF6)){padding->
  LazyColumn(Modifier.fillMaxSize().padding(padding).safeDrawingPadding(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
   item{TextButton(onClick=onBack){Text("‹ Profile")};Text("Ratings & complaints",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Feedback on your meals. Zomeal handles callbacks and refund decisions.");TextButton(onClick={refresh++}){Text("Refresh")}}
   if(loading)item{CircularProgressIndicator()}
   error?.let{item{Text(it,color=Color(0xFFB3261E))}}
   if(!loading&&rows.length()==0)item{Text("No feedback on this page yet.")}
   items(rows.length()){i->val r=rows.getJSONObject(i)
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)){
     Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Text(r.optString("item_name"),fontWeight=FontWeight.Bold);Text(r.optString("provider_name"))
      Text("${r.optString("service_date")} · ${r.optString("meal_slot")}")
      Text(String.format(Locale.ENGLISH,"Meal price: ₹%.2f",r.optLong("meal_value_paise")/100.0))
      Text(r.optString("customer_name"))
      Text(if(r.isNull("rating"))"Not rated" else "★ ${r.optInt("rating")} / 5",color=Color(0xFF087F43),fontWeight=FontWeight.Bold)
      if(!r.isNull("review_text"))Text(r.optString("review_text"))
      if(!r.isNull("photo_path"))FeedbackPhoto(repository,r.optString("photo_path"),false)
      if(!r.isNull("complaint_id")){
       Text(r.optString("status").replace('_',' '),fontWeight=FontWeight.Bold)
       Text(r.optString("issue"),fontWeight=FontWeight.Bold);Text(r.optString("details"))
       val photos=r.optJSONArray("photo_paths")?:JSONArray()
       for(n in 0 until photos.length())FeedbackPhoto(repository,photos.getString(n),true)
       if(r.optLong("refund_paise")>0)Text(String.format(Locale.ENGLISH,"Refund deducted from earnings: ₹%.2f",r.optLong("refund_paise")/100.0))
      }
     }
    }
   }
   item{Row{TextButton(enabled=offset>0&&!loading,onClick={offset-=50}){Text("Previous")};TextButton(enabled=rows.length()==50&&!loading,onClick={offset+=50}){Text("Next")}}}
  }
 }
}
@Composable private fun FeedbackPhoto(repository:SupabaseProviderRepository,path:String,evidence:Boolean){
 var bitmap by remember(path){mutableStateOf<android.graphics.Bitmap?>(null)}
 LaunchedEffect(path){repository.approvedMedia(path,evidence){bitmap=it}}
 bitmap?.let{Image(it.asImageBitmap(),if(evidence)"Customer food photo" else "Menu photo",Modifier.fillMaxWidth().height(160.dp))}
}
